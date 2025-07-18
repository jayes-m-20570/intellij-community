// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.reference;

import com.intellij.analysis.AnalysisBundle;
import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInsight.daemon.ProblemHighlightFilter;
import com.intellij.codeInspection.DefaultInspectionToolResultExporter;
import com.intellij.codeInspection.GlobalInspectionContext;
import com.intellij.codeInspection.ProblemDescriptorUtil;
import com.intellij.codeInspection.lang.InspectionExtensionsFactory;
import com.intellij.codeInspection.lang.RefManagerExtension;
import com.intellij.ide.scratch.ScratchUtil;
import com.intellij.lang.Language;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.diagnostic.Attachment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.diagnostic.RuntimeExceptionWithAttachments;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtilCore;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Segment;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileWithId;
import com.intellij.psi.*;
import com.intellij.psi.impl.light.LightElement;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.Interner;
import one.util.streamex.EntryStream;
import org.jdom.Element;
import org.jetbrains.annotations.Async;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class RefManagerImpl extends RefManager {
  public static final ExtensionPointName<RefGraphAnnotator> EP_NAME = ExtensionPointName.create("com.intellij.refGraphAnnotator");
  private static final Logger LOG = Logger.getInstance(RefManagerImpl.class);

  private long myLastUsedMask = 0b1000_00000000_00000000_00000000; // 28th bit, guarded by this

  private final @NotNull Project myProject;
  private AnalysisScope myScope;
  private RefProject myRefProject;

  private final Set<VirtualFile> myUnprocessedFiles = VfsUtilCore.createCompactVirtualFileSet();
  private final boolean processExternalElements = Registry.is("batch.inspections.process.external.elements");
  private final ConcurrentHashMap<PsiAnchor, RefElement> myRefTable = new ConcurrentHashMap<>();

  private volatile List<RefElement> myCachedSortedRefs; // holds cached values from myPsiToRefTable/myRefTable sorted by containing virtual file; benign data race

  private final ConcurrentMap<Module, RefModule> myModules = new ConcurrentHashMap<>();
  private final ProjectIterator myProjectIterator = new ProjectIterator();
  private final AtomicBoolean myDeclarationsFound = new AtomicBoolean(false);
  private final PsiManager myPsiManager;

  private volatile boolean myIsInProcess;
  private volatile boolean myOfflineView;

  private final List<RefGraphAnnotator> myGraphAnnotators = ContainerUtil.createConcurrentList();
  private GlobalInspectionContext myContext;

  private final Map<Key<?>, RefManagerExtension<?>> myExtensions = new HashMap<>();
  private final Map<Language, RefManagerExtension<?>> myLanguageExtensions = new HashMap<>();
  private final Interner<String> myNameInterner = Interner.createStringInterner();

  private final BlockingQueue<@NotNull Runnable> myTasks;
  private final AtomicInteger myTasksInFlight;
  private final ExecutorService myExecutor;
  private final CountDownLatch myLatch;

  public RefManagerImpl(@NotNull Project project, @Nullable AnalysisScope scope, @NotNull GlobalInspectionContext context) {
    myProject = project;
    myScope = scope;
    myContext = context;
    myPsiManager = PsiManager.getInstance(project);
    myRefProject = new RefProjectImpl(this);
    for (InspectionExtensionsFactory factory : InspectionExtensionsFactory.EP_NAME.getExtensionList()) {
      final RefManagerExtension<?> extension = factory.createRefManagerExtension(this);
      if (extension != null) {
        myExtensions.put(extension.getID(), extension);
        for (Language language : extension.getLanguages()) {
          myLanguageExtensions.put(language, extension);
        }
      }
    }
    if (scope != null) {
      for (Module module : ModuleManager.getInstance(getProject()).getModules()) {
        getRefModule(module);
      }
    }
    if (Registry.is("batch.inspections.process.project.usages.in.parallel")) {
      final int setting = Registry.get("batch.inspections.number.of.threads").asInteger();
      final int threadsCount = (setting > 0) ? setting : Runtime.getRuntime().availableProcessors() - 1;
      myExecutor =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("Reference Graph Executor", Math.min(Math.max(threadsCount, 1), 10));
      myTasksInFlight = new AtomicInteger();
      // unbounded queue because tasks are submitted under read action, so we mustn't block
      myTasks = new LinkedBlockingQueue<>();
      myLatch = new CountDownLatch(1);
    }
    else {
      myExecutor = null;
      myTasksInFlight = null;
      myTasks = null;
      myLatch = null;
    }
  }

  String internName(@NotNull String name) {
    synchronized (myNameInterner) {
      return myNameInterner.intern(name);
    }
  }

  public @NotNull GlobalInspectionContext getContext() {
    return myContext;
  }

  @Override
  public void iterate(@NotNull RefVisitor visitor) {
    for (RefElement refElement : getSortedElements()) {
      refElement.accept(visitor);
    }
    List<RefModule> filteredModules =
      ContainerUtil.filter(myModules.values(), refModule -> ReadAction.compute(() -> myScope.containsModule(refModule.getModule())));
    for (RefModule refModule : filteredModules) {
      refModule.accept(visitor);
    }
    for (RefManagerExtension<?> extension : myExtensions.values()) {
      extension.iterate(visitor);
    }
  }

  public void cleanup() {
    myScope = null;
    myRefProject = null;
    myRefTable.clear();
    myCachedSortedRefs = null;
    myModules.clear();
    myContext = null;

    myGraphAnnotators.clear();
    for (RefManagerExtension<?> extension : myExtensions.values()) {
      extension.cleanup();
    }
    myExtensions.clear();
    myLanguageExtensions.clear();
  }

  @Override
  public @Nullable AnalysisScope getScope() {
    return myScope;
  }

  void fireNodeInitialized(RefElement refElement) {
    if (!myIsInProcess || !isDeclarationsFound()) {
      return;
    }
    final PsiElement psi = refElement.getPsiElement();
    if (psi != null) {
      for (RefManagerExtension<?> each : myExtensions.values()) {
        each.onEntityInitialized(refElement, psi);
      }
    }
    for (RefGraphAnnotator annotator : myGraphAnnotators) {
      annotator.onInitialize(refElement);
    }
  }

  public void fireNodeMarkedReferenced(RefElement refWhat,
                                       RefElement refFrom,
                                       boolean referencedFromClassInitializer,
                                       boolean forReading,
                                       boolean forWriting) {
    for (RefGraphAnnotator annotator : myGraphAnnotators) {
      annotator.onMarkReferenced(refWhat, refFrom, referencedFromClassInitializer, forReading, forWriting);
    }
  }

  public void fireNodeMarkedReferenced(RefElement refWhat,
                                       RefElement refFrom,
                                       boolean referencedFromClassInitializer,
                                       boolean forReading,
                                       boolean forWriting,
                                       PsiElement element) {
    for (RefGraphAnnotator annotator : myGraphAnnotators) {
      annotator.onMarkReferenced(refWhat, refFrom, referencedFromClassInitializer, forReading, forWriting, element);
    }
  }

  public void fireAnonymousReferenced(RefElement refFrom,
                                      boolean referencedFromClassInitializer,
                                      boolean forReading,
                                      boolean forWriting,
                                      PsiElement element) {
    for (RefGraphAnnotator annotator : myGraphAnnotators) {
      annotator.onAnonymousReferenced(refFrom, referencedFromClassInitializer, forReading, forWriting, element);
    }
  }

  public void fireNodeMarkedReferenced(PsiElement what, PsiElement from) {
    for (RefGraphAnnotator annotator : myGraphAnnotators) {
      annotator.onMarkReferenced(what, from, false);
    }
  }

  private void fireBuildReferences(RefElement refElement) {
    for (RefGraphAnnotator annotator : myGraphAnnotators) {
      annotator.onReferencesBuild(refElement);
    }
  }

  public void registerGraphAnnotator(@NotNull RefGraphAnnotator annotator) {
    if (!myGraphAnnotators.contains(annotator)) {
      myGraphAnnotators.add(annotator);
      if (annotator instanceof RefGraphAnnotatorEx annotatorEx) {
        annotatorEx.initialize(this);
      }
    }
  }

  public void unregisterAnnotator(RefGraphAnnotator annotator) {
    myGraphAnnotators.remove(annotator);
  }

  @Override
  public synchronized long getLastUsedMask() {
    if (myLastUsedMask < 0) {
      throw new IllegalStateException("We're out of 64 bits, sorry");
    }
    myLastUsedMask <<= 1;
    return myLastUsedMask;
  }

  @Override
  public <T> T getExtension(@NotNull Key<T> key) {
    //noinspection unchecked
    return (T)myExtensions.get(key);
  }

  @Override
  public @Nullable String getType(@NotNull RefEntity ref) {
    for (RefManagerExtension<?> extension : myExtensions.values()) {
      final String type = extension.getType(ref);
      if (type != null) return type;
    }
    if (ref instanceof RefFile) {
      return SmartRefElementPointer.FILE;
    }
    if (ref instanceof RefModule) {
      return SmartRefElementPointer.MODULE;
    }
    if (ref instanceof RefProject) {
      return SmartRefElementPointer.PROJECT;
    }
    if (ref instanceof RefDirectory) {
      return SmartRefElementPointer.DIR;
    }
    return null;
  }

  @Override
  public @NotNull RefEntity getRefinedElement(@NotNull RefEntity ref) {
    for (RefManagerExtension<?> extension : myExtensions.values()) {
      ref = extension.getRefinedElement(ref);
    }
    return ref;
  }

  @Override
  public @Nullable Element export(@NotNull RefEntity refEntity, int actualLine) {
    refEntity = getRefinedElement(refEntity);

    Element problem = new Element("problem");

    if (refEntity instanceof RefDirectory dir) {
      Element fileElement = new Element("file");
      VirtualFile virtualFile = ((PsiDirectory)dir.getPsiElement()).getVirtualFile();
      fileElement.addContent(virtualFile.getUrl());
      problem.addContent(fileElement);
    }
    else if (refEntity instanceof RefElement refElement) {
      final SmartPsiElementPointer<?> pointer = refElement.getPointer();
      if (pointer == null) return null;
      PsiFile psiFile = pointer.getContainingFile();
      if (psiFile == null) return null;

      Element fileElement = new Element("file");
      final VirtualFile virtualFile = psiFile.getVirtualFile();
      LOG.assertTrue(virtualFile != null);
      fileElement.addContent(virtualFile.getUrl());
      problem.addContent(fileElement);

      int resultLine;
      if (actualLine == -1) {
        final Document document = PsiDocumentManager.getInstance(pointer.getProject()).getDocument(psiFile);
        LOG.assertTrue(document != null);
        final Segment range = pointer.getRange();
        resultLine = range == null ? -1 : document.getLineNumber(range.getStartOffset()) + 1;
      }
      else {
        resultLine = actualLine + 1;
      }

      Element lineElement = new Element("line");
      lineElement.addContent(String.valueOf(resultLine));
      problem.addContent(lineElement);

      appendModule(problem, refElement.getModule());
    }
    else if (refEntity instanceof RefModule refModule) {
      final VirtualFile moduleFile = refModule.getModule().getModuleFile();
      final Element fileElement = new Element("file");
      fileElement.addContent(moduleFile != null ? moduleFile.getUrl() : refEntity.getName());
      problem.addContent(fileElement);
      appendModule(problem, refModule);
    }

    for (RefManagerExtension<?> extension : myExtensions.values()) {
      extension.export(refEntity, problem);
    }

    new SmartRefElementPointerImpl(refEntity, true).writeExternal(problem);
    return problem;
  }

  @Override
  public @Nullable Element export(@NotNull RefEntity entity) {
    Element element = export(entity, -1);
    if (element == null) return null;

    if (!(entity instanceof RefElement refElement)) return element;

    SmartPsiElementPointer<?> pointer = refElement.getPointer();

    PsiElement psiElement = pointer.getElement();

    Element language = new Element(DefaultInspectionToolResultExporter.INSPECTION_RESULTS_LANGUAGE);
    language.addContent(psiElement != null ? psiElement.getLanguage().getID() : "");
    element.addContent(language);

    PsiFile psiFile = pointer.getContainingFile();

    if (psiFile == null) return element;

    Document document = PsiDocumentManager.getInstance(pointer.getProject()).getDocument(psiFile);
    if (document == null) return element;

    Segment range = pointer.getRange();
    if (range == null) return element;

    int firstRangeLine = document.getLineNumber(range.getStartOffset());
    int lineStartOffset = document.getLineStartOffset(firstRangeLine);
    int endOffset = Math.min(range.getEndOffset(), document.getLineEndOffset(firstRangeLine));

    TextRange exportedRange = new TextRange(range.getStartOffset(), endOffset);
    String text = ProblemDescriptorUtil.extractHighlightedText(exportedRange, psiFile);

    element.addContent(new Element("offset").addContent(String.valueOf(exportedRange.getStartOffset() - lineStartOffset)));
    element.addContent(new Element("length").addContent(String.valueOf(exportedRange.getLength())));
    element.addContent(new Element("highlighted_element").addContent(ProblemDescriptorUtil.sanitizeIllegalXmlChars(text)));

    return element;
  }

  @Override
  public @Nullable String getGroupName(@NotNull RefElement entity) {
    for (RefManagerExtension<?> extension : myExtensions.values()) {
      final String groupName = extension.getGroupName(entity);
      if (groupName != null) return groupName;
    }

    RefEntity parent = entity.getOwner();
    while (parent != null && !(parent instanceof RefDirectory)) {
      parent = parent.getOwner();
    }
    final LinkedList<String> containingDirs = new LinkedList<>();
    while (parent instanceof RefDirectory) {
      containingDirs.addFirst(parent.getName());
      parent = parent.getOwner();
    }
    return containingDirs.isEmpty() ? null : StringUtil.join(containingDirs, "/");
  }

  private static void appendModule(Element problem, RefModule refModule) {
    if (refModule != null) {
      Element moduleElement = new Element("module");
      moduleElement.addContent(refModule.getName());
      problem.addContent(moduleElement);
    }
  }

  public void findAllDeclarations() {
    final AnalysisScope scope = getScope();
    if (scope == null) {
      return;
    }
    if (!myDeclarationsFound.getAndSet(true)) {
      long before = System.currentTimeMillis();
      startTaskWorkers();
      if (!Registry.is("batch.inspections.visit.psi.in.parallel")) {
        scope.accept(myProjectIterator);
      }
      else {
        final PsiManager psiManager = PsiManager.getInstance(myProject);
        scope.accept(vFile -> {
          executeTask(() -> {
            final PsiFile file = psiManager.findFile(vFile);
            if (file != null && ProblemHighlightFilter.shouldProcessFileInBatch(file)) {
              file.accept(myProjectIterator);
            }
          });
          return true;
        });
        waitForWorkersToFinish();
      }
      LOG.info("Total duration of processing project usages: " + (System.currentTimeMillis() - before) + "ms");
    }
  }

  private void waitForWorkersToFinish() {
    if (myTasksInFlight.decrementAndGet() == 0) return;
    while (true) {
      try {
        ProgressManager.checkCanceled();
        myLatch.await(100, TimeUnit.MILLISECONDS);
        if (myTasksInFlight.intValue() == 0) return;
      }
      catch (InterruptedException ignore) {}
    }
  }

  public void buildReferences(RefElement element) {
    if (element.areReferencesBuilt()) return;
    executeTask(() -> {
      element.initializeIfNeeded();
      element.buildReferences();
      fireBuildReferences(element);
    });
  }

  @Override
  public void executeTask(@Async.Schedule @NotNull Runnable runnable) {
    if (myTasks != null) {
      myTasksInFlight.incrementAndGet();
      try {
        myTasks.put(runnable);
      }
      catch (InterruptedException ignore) {}
    }
    else {
      runnable.run();
    }
  }

  private void startTaskWorkers() {
    if (myExecutor == null) return;
    myTasksInFlight.incrementAndGet();
    ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
    ProgressIndicator progressIndicator = indicator == null && ApplicationManager.getApplication().isUnitTestMode()
                                          ? new EmptyProgressIndicator()
                                          : indicator;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      while (myTasksInFlight.intValue() != 0) {
        try {
          final Runnable task = myTasks.poll(50, TimeUnit.MILLISECONDS);
          ProgressManager.checkCanceled();
          if (task != null) {
            runTask(progressIndicator, task);
          }
        }
        catch (InterruptedException ignore) {}
      }
    });
  }

  private void runTask(ProgressIndicator progressIndicator, @Async.Execute Runnable task) {
    ReadAction.nonBlocking(() -> {
        try {
          task.run();
        }
        catch (CancellationException e) {
          throw e;
        }
        catch (Throwable e) {
          LOG.error(e);
        }
      })
      .inSmartMode(myProject)
      .wrapProgress(progressIndicator)
      .submit(myExecutor)
      .onSuccess(x -> {
        if (myTasksInFlight.decrementAndGet() == 0) myLatch.countDown();
      });
  }

  public boolean isDeclarationsFound() {
    return myDeclarationsFound.get();
  }

  public void runInsideInspectionReadAction(@NotNull Runnable runnable) {
    myIsInProcess = true;
    try {
      runnable.run();
    }
    finally {
      myIsInProcess = false;
      if (myScope != null) {
        myScope.invalidate();
      }
      myCachedSortedRefs = null;
    }
  }

  public void startOfflineView() {
    myOfflineView = true;
  }

  public boolean isOfflineView() {
    return myOfflineView;
  }

  @Override
  public @NotNull Project getProject() {
    return myProject;
  }

  @Override
  public @NotNull RefProject getRefProject() {
    return myRefProject;
  }

  public @NotNull List<RefElement> getSortedElements() {
    List<RefElement> answer = myCachedSortedRefs;
    if (answer != null) return answer;

    Map<VirtualFile, List<RefElement>> map = new HashMap<>();
    for (RefElement ref : getElements()) {
      map.computeIfAbsent(((RefElementImpl)ref).getVirtualFile(), k -> new ArrayList<>()).add(ref);
    }
    for (List<RefElement> elementsInFile : map.values()) {
      if (elementsInFile.size() > 1) {
        ReadAction.run(() -> {
          elementsInFile.sort(
            Comparator.comparing(o -> ObjectUtils.notNull(o.getPointer().getRange(), TextRange.EMPTY_RANGE),
                                 Segment.BY_START_OFFSET_THEN_END_OFFSET));
        });
      }
    }
    return myCachedSortedRefs = Collections.unmodifiableList(EntryStream.of(map)
      .sorted((e1, e2) -> VfsUtilCore.compareByPath(e1.getKey(), e2.getKey()))
      .values().toFlatList(Function.identity()));
  }

  public @NotNull List<RefElement> getElements() {
    return new ArrayList<>(myRefTable.values());
  }

  @Override
  public @NotNull PsiManager getPsiManager() {
    return myPsiManager;
  }

  @Override
  public synchronized boolean isInGraph(VirtualFile file) {
    return !myUnprocessedFiles.contains(file);
  }

  @Override
  public @Nullable PsiNamedElement getContainerElement(@NotNull PsiElement element) {
    Language language = element.getLanguage();
    RefManagerExtension<?> extension = myLanguageExtensions.get(language);
    if (extension == null) return null;
    return extension.getElementContainer(element);
  }

  private synchronized void registerUnprocessed(VirtualFile virtualFile) {
    myUnprocessedFiles.add(virtualFile);
  }

  private void removeReference(@NotNull RefElement refElem) {
    final PsiElement element = refElem.getPsiElement();
    final RefManagerExtension<?> extension = element != null ? getExtension(element.getLanguage()) : null;
    if (extension != null) {
      extension.removeReference(refElem);
    }

    if (element != null && myRefTable.remove(createAnchor(element)) != null) return;

    //PsiElement may have been invalidated and new one returned by getElement() is different so we need to do this stuff.
    for (Map.Entry<PsiAnchor, RefElement> entry : myRefTable.entrySet()) {
      RefElement value = entry.getValue();
      PsiAnchor anchor = entry.getKey();
      if (value == refElem) {
        myRefTable.remove(anchor);
        break;
      }
    }
    myCachedSortedRefs = null;
  }

  private static @NotNull PsiAnchor createAnchor(@NotNull PsiElement element) {
    return ReadAction.compute(() -> PsiAnchor.create(element));
  }

  public void initializeAnnotators() {
    for (RefGraphAnnotator annotator : EP_NAME.getExtensionList()) {
      registerGraphAnnotator(annotator);
    }
  }

  private class ProjectIterator extends PsiElementVisitor {

    @Override
    public void visitElement(@NotNull PsiElement element) {
      ProgressManager.checkCanceled();
      final RefManagerExtension<?> extension = getExtension(element.getLanguage());
      if (extension != null) {
        PsiElement current = element;
        while (current != null) {
          extension.visitElement(current);
          current = depthFirstNext(current, element);
        }
      }
      else if (processExternalElements) {
        processExternalElements(element);
      }
    }

    private void processExternalElements(@NotNull PsiElement element) {
      PsiFile file = element.getContainingFile();
      if (file != null) {
        RefManagerExtension<?> externalFileManagerExtension =
          ContainerUtil.find(myExtensions.values(), ex -> ex.shouldProcessExternalFile(file));
        if (externalFileManagerExtension == null) {
          if (element instanceof PsiFile) {
            VirtualFile virtualFile = PsiUtilCore.getVirtualFile(element);
            if (virtualFile instanceof VirtualFileWithId) {
              registerUnprocessed(virtualFile);
            }
          }
        } else {
          RefElement refFile = getReference(file);
          LOG.assertTrue(refFile != null, file);
          PsiElement current = element;
          while (current != null) {
            for (PsiReference reference : current.getReferences()) {
              PsiElement resolve = reference.resolve();
              if (resolve != null) {
                fireNodeMarkedReferenced(resolve, file);
                RefElement refWhat = getReference(resolve);
                if (refWhat == null) {
                  PsiFile targetContainingFile = resolve.getContainingFile();
                  //no logic to distinguish different elements in the file anyway
                  if (file == targetContainingFile) continue;
                  refWhat = getReference(targetContainingFile);
                }

                if (refWhat != null) {
                  ((WritableRefElement)refWhat).addInReference(refFile);
                  ((WritableRefElement)refFile).addOutReference(refWhat);
                }
              }
            }
            current = depthFirstNext(current, element);
          }
          Stream<? extends PsiElement> implicitRefs = externalFileManagerExtension.extractExternalFileImplicitReferences(file);
          implicitRefs.forEach(e -> {
            RefElement superClassReference = getReference(e);
            if (superClassReference != null) {
              //in case of implicit inheritance, e.g. GroovyObject
              //= no explicit reference is provided, dependency on groovy library could be treated as redundant though it is not
              //inReference is not important in this case
              ((RefElementImpl)refFile).addOutReference(superClassReference);
            }
          });

          if (element instanceof PsiFile) {
            externalFileManagerExtension.markExternalReferencesProcessed(refFile);
          }
        }
      }
    }

    private static PsiElement depthFirstNext(PsiElement current, PsiElement root) {
      PsiElement child = current.getFirstChild();
      if (child != null) return child;
      if (current == root) return null;
      PsiElement sibling = current.getNextSibling();
      if (sibling != null) return sibling;
      while (true) {
        PsiElement parent = current.getParent();
        if (parent == root || parent == null) return null;
        PsiElement parentSibling = parent.getNextSibling();
        if (parentSibling != null) return parentSibling;
        current = parent;
      }
    }

    @Override
    public void visitFile(@NotNull PsiFile psiFile) {
      if (!(psiFile instanceof PsiBinaryFile) && !psiFile.getFileType().isBinary()) {
        final FileViewProvider viewProvider = psiFile.getViewProvider();
        final Set<Language> relevantLanguages = viewProvider.getLanguages();
        for (Language language : relevantLanguages) {
          try {
            visitElement(viewProvider.getPsi(language));
          }
          catch (ProcessCanceledException | IndexNotReadyException e) {
            throw e;
          }
          catch (Throwable e) {
            if (ApplicationManager.getApplication().isHeadlessEnvironment()) {
              LOG.error(psiFile.getName(), e);
            }
            else {
              LOG.error(new RuntimeExceptionWithAttachments(e, new Attachment("diagnostics.txt", psiFile.getName())));
            }
          }
        }
        myPsiManager.dropResolveCaches();
      }
      final VirtualFile virtualFile = psiFile.getVirtualFile();
      if (virtualFile != null) {
        executeTask(() -> {
          String relative =
            ProjectUtilCore.displayUrlRelativeToProject(virtualFile, virtualFile.getPresentableUrl(), myProject, true, false);
          myContext.incrementJobDoneAmount(myContext.getStdJobDescriptors().BUILD_GRAPH, relative);
        });
      }
    }
  }

  @Override
  public @Nullable RefElement getReference(@Nullable PsiElement elem) {
    return getReference(elem, false);
  }

  public @Nullable RefElement getReference(PsiElement elem, boolean ignoreScope) {
    if (ReadAction.compute(() -> elem == null || !elem.isValid() ||
                                 elem instanceof LightElement || !(elem instanceof PsiDirectory) && !belongsToScope(elem, ignoreScope))) {
      return null;
    }

    return getFromRefTableOrCache(
      elem,
      () -> ReadAction.compute(() -> {
        final RefManagerExtension<?> extension = getExtension(elem.getLanguage());
        if (extension != null) {
          final RefElement refElement = extension.createRefElement(elem);
          if (refElement != null) return (RefElementImpl)refElement;
        }
        if (elem instanceof PsiFile file) {
          return new RefFileImpl(file, this);
        }
        if (elem instanceof PsiDirectory dir) {
          return new RefDirectoryImpl(dir, this);
        }
        return null;
      }),
      element -> ReadAction.run(() -> element.initializeIfNeeded()));
  }

  private RefManagerExtension<?> getExtension(Language language) {
    return myLanguageExtensions.get(language);
  }

  @Override
  public @Nullable RefEntity getReference(String type, String fqName) {
    for (RefManagerExtension<?> extension : myExtensions.values()) {
      final RefEntity refEntity = extension.getReference(type, fqName);
      if (refEntity != null) return refEntity;
    }
    if (SmartRefElementPointer.FILE.equals(type)) {
      return RefFileImpl.fileFromExternalName(this, fqName);
    }
    if (SmartRefElementPointer.MODULE.equals(type)) {
      return RefModuleImpl.moduleFromName(this, fqName);
    }
    if (SmartRefElementPointer.PROJECT.equals(type)) {
      return getRefProject();
    }
    if (SmartRefElementPointer.DIR.equals(type)) {
      String url = VfsUtilCore.pathToUrl(PathMacroManager.getInstance(getProject()).expandPath(fqName));
      VirtualFile vFile = VirtualFileManager.getInstance().findFileByUrl(url);
      if (vFile != null) {
        final PsiDirectory dir = PsiManager.getInstance(getProject()).findDirectory(vFile);
        return getReference(dir);
      }
    }
    return null;
  }

  public @Nullable <T extends RefElement> T getFromRefTableOrCache(@NotNull PsiElement element,
                                                                   @NotNull Supplier<@Nullable T> factory) {
    return getFromRefTableOrCache(element, factory, null);
  }

  public @Nullable <T extends RefElement> T getFromRefTableOrCache(@NotNull PsiElement element,
                                                                   @NotNull Supplier<@Nullable T> factory,
                                                                   @Nullable Consumer<? super T> whenCached) {
    PsiAnchor psiAnchor = createAnchor(element);
    //noinspection unchecked
    T result = (T)myRefTable.get(psiAnchor);
    if (result != null) return result;

    if (!isValidPointForReference()) {
      //LOG.assertTrue(true, "References may become invalid after process is finished");
      return null;
    }

    T newElement = factory.get();
    if (newElement == null) return null;

    myCachedSortedRefs = null;
    RefElement prev = myRefTable.putIfAbsent(psiAnchor, newElement);
    if (prev != null) {
      //noinspection unchecked
      return (T)prev;
    }
    if (whenCached != null) {
      whenCached.accept(newElement);
    }

    return newElement;
  }

  @Override
  public RefModule getRefModule(@Nullable Module module) {
    if (module == null) {
      return null;
    }
    RefModule refModule = myModules.get(module);
    if (refModule == null) {
      refModule = ConcurrencyUtil.cacheOrGet(myModules, module, new RefModuleImpl(module, this));
    }
    return refModule;
  }

  @Override
  public boolean belongsToScope(PsiElement psiElement) {
    return belongsToScope(psiElement, false);
  }

  private boolean belongsToScope(PsiElement psiElement, boolean ignoreScope) {
    if (psiElement == null || !psiElement.isValid()) return false;
    if (psiElement instanceof PsiCompiledElement) return false;
    final PsiFile containingFile = ReadAction.compute(psiElement::getContainingFile);
    if (containingFile == null) {
      return false;
    }
    for (RefManagerExtension<?> extension : myExtensions.values()) {
      if (!extension.belongsToScope(psiElement)) return false;
    }
    final Boolean inProject = ReadAction.compute(() -> psiElement.getManager().isInProject(psiElement));
    return (inProject.booleanValue() || ScratchUtil.isScratch(containingFile.getVirtualFile())) &&
           (ignoreScope || getScope() == null || getScope().contains(psiElement));
  }

  @Override
  public String getQualifiedName(RefEntity refEntity) {
    if (refEntity == null || refEntity instanceof RefElementImpl && !refEntity.isValid()) {
      return AnalysisBundle.message("inspection.reference.invalid");
    }

    return refEntity.getQualifiedName();
  }

  @Override
  public void removeRefElement(@NotNull RefElement refElement, @NotNull List<? super RefElement> deletedRefs) {
    refElement.initializeIfNeeded();
    List<RefEntity> children = refElement.getChildren();
    RefElement[] refElements = children.toArray(new RefElement[0]);
    for (RefElement refChild : refElements) {
      removeRefElement(refChild, deletedRefs);
    }

    ((RefManagerImpl)refElement.getRefManager()).removeReference(refElement);
    ((RefElementImpl)refElement).referenceRemoved();
    if (!deletedRefs.contains(refElement)) {
      deletedRefs.add(refElement);
    }
    else {
      LOG.error("deleted second time");
    }
  }

  public boolean isValidPointForReference() {
    return myIsInProcess || myOfflineView || ApplicationManager.getApplication().isUnitTestMode();
  }
}
