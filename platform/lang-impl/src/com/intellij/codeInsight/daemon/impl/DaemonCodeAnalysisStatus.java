// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl;

import com.intellij.psi.PsiFile;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.TestOnly;

@ApiStatus.Internal
public interface DaemonCodeAnalysisStatus {
  @TestOnly
  @RequiresEdt
  boolean isRunningOrPending();
  @TestOnly
  boolean isAllAnalysisFinished(PsiFile psiFile);
}
