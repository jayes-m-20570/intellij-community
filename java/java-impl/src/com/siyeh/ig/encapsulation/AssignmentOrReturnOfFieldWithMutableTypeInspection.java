// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.siyeh.ig.encapsulation;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.dataFlow.Mutability;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.JavaPsiRecordUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.containers.ContainerUtil;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.psiutils.ClassUtils;
import com.siyeh.ig.psiutils.CollectionUtils;
import com.siyeh.ig.psiutils.TypeUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.intellij.codeInspection.options.OptPane.checkbox;
import static com.intellij.codeInspection.options.OptPane.pane;

/**
 * @author Bas Leijdekkers
 */
public final class AssignmentOrReturnOfFieldWithMutableTypeInspection extends BaseInspection {

  public static final String[] MUTABLE_TYPES = {
    CommonClassNames.JAVA_UTIL_DATE,
    CommonClassNames.JAVA_UTIL_CALENDAR,
    CommonClassNames.JAVA_UTIL_COLLECTION,
    CommonClassNames.JAVA_UTIL_MAP,
    "com.google.common.collect.Multimap",
    "com.google.common.collect.Table"
  };

  @SuppressWarnings("PublicField")
  public boolean ignorePrivateMethods = true;

  @Override
  public @NotNull String buildErrorString(Object... infos) {
    if (infos[0] instanceof PsiRecordComponent component) {
      final int reportAssignment = (boolean)infos[1] ? 0b01 : 0;
      final int reportReturn = (boolean)infos[2] ? 0b10 : 0;
      final String type = component.getType().getPresentableText();
      return InspectionGadgetsBundle.message("assignment.and.return.of.mutable.record.component", reportAssignment | reportReturn, type);
    }
    else {
      final PsiField field = (PsiField)infos[0];
      final PsiExpression rhs = (PsiExpression)infos[1];
      final PsiType type = field.getType();
      final boolean assignment = ((Boolean)infos[3]).booleanValue();
      return assignment
             ? InspectionGadgetsBundle.message("assignment.of.field.with.mutable.type.problem.descriptor",
                                               type.getPresentableText(), field.getName(), rhs.getText())
             : InspectionGadgetsBundle.message("return.of.field.with.mutable.type.problem.descriptor",
                                               type.getPresentableText(), field.getName());
    }
  }

  @Override
  protected @Nullable LocalQuickFix buildFix(Object... infos) {
    if (infos[0] instanceof PsiRecordComponent) return null;
    final PsiReferenceExpression returnValue = (PsiReferenceExpression)infos[1];
    final String type = (String)infos[2];
    if (CommonClassNames.JAVA_UTIL_DATE.equals(type) ||
        CommonClassNames.JAVA_UTIL_CALENDAR.equals(type) ||
        returnValue.getType() instanceof PsiArrayType)  {
      final PsiField field = (PsiField)infos[0];
      return new ReplaceWithCloneFix(field.getName());
    }
    final boolean assignment = ((Boolean)infos[3]).booleanValue();
    if (!assignment) {
      return ReturnOfCollectionFieldFix.build(returnValue);
    }
    return null;
  }

  @Override
  public @NotNull OptPane getOptionsPane() {
    return pane(
      checkbox("ignorePrivateMethods", InspectionGadgetsBundle.message("ignore.private.methods.option")));
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new AssignmentOrReturnOfFieldWithMutableTypeVisitor();
  }

  private class AssignmentOrReturnOfFieldWithMutableTypeVisitor extends BaseInspectionVisitor {

    @Override
    public void visitAssignmentExpression(@NotNull PsiAssignmentExpression expression) {
      super.visitAssignmentExpression(expression);
      final IElementType tokenType = expression.getOperationTokenType();
      if (!JavaTokenType.EQ.equals(tokenType)) {
        return;
      }
      final PsiExpression lhs = PsiUtil.deparenthesizeExpression(expression.getLExpression());
      if (!(lhs instanceof PsiReferenceExpression lRef)) {
        return;
      }
      final String type = TypeUtils.expressionHasTypeOrSubtype(lhs, MUTABLE_TYPES);
      if (type == null && !(lhs.getType() instanceof PsiArrayType)) {
        return;
      }
      final PsiExpression rhs = PsiUtil.deparenthesizeExpression(expression.getRExpression());
      if (!(rhs instanceof PsiReferenceExpression rRef)) {
        return;
      }
      if (!(lRef.resolve() instanceof PsiField field)) return;
      if (!(rRef.resolve() instanceof PsiParameter parameter)
          || !(parameter.getDeclarationScope() instanceof PsiMethod)
          || ClassUtils.isImmutable(parameter.getType())) {
        return;
      }
      if (ignorePrivateMethods) {
        final PsiMethod containingMethod = PsiTreeUtil.getParentOfType(expression, PsiMethod.class);
        if (containingMethod == null || containingMethod.hasModifierProperty(PsiModifier.PRIVATE)) {
          return;
        }
        if (containingMethod.isConstructor()) {
          final PsiClass containingClass = containingMethod.getContainingClass();
          if (containingClass != null && containingClass.hasModifierProperty(PsiModifier.PRIVATE)) {
            return;
          }
        }
      }
      registerError(rhs, field, rhs, type, Boolean.TRUE);
    }

    @Override
    public void visitReturnStatement(@NotNull PsiReturnStatement statement) {
      super.visitReturnStatement(statement);
      final PsiExpression returnValue = PsiUtil.deparenthesizeExpression(statement.getReturnValue());
      if (!(returnValue instanceof PsiReferenceExpression ref)) {
        return;
      }
      final PsiElement element = PsiTreeUtil.getParentOfType(statement, PsiMethod.class, PsiLambdaExpression.class);
      if (ignorePrivateMethods && element instanceof PsiMethod m && m.hasModifierProperty(PsiModifier.PRIVATE)) {
        return;
      }
      if (!(ref.resolve() instanceof PsiField field)) return;
      final String type = TypeUtils.expressionHasTypeOrSubtype(returnValue, MUTABLE_TYPES);
      if (type == null && !(returnValue.getType() instanceof PsiArrayType)) return;
      if (CollectionUtils.isConstantEmptyArray(field) ||
          ClassUtils.isImmutable(field.getType()) ||
          Mutability.getMutability(field).isUnmodifiable()) {
        return;
      }
      if (field.hasModifierProperty(PsiModifier.FINAL)
          && field.hasModifierProperty(PsiModifier.STATIC)
          && field.getType() instanceof PsiArrayType
          && field.getInitializer() == null) {
        return;
      }
      PsiElement nameElement = ref.getReferenceNameElement();
      if (nameElement == null) return;
      registerError(nameElement, field, returnValue, type, Boolean.FALSE);
    }

    @Override
    public void visitRecordHeader(@NotNull PsiRecordHeader recordHeader) {
      super.visitRecordHeader(recordHeader);
      final PsiClass recordClass = recordHeader.getContainingClass();
      if (recordClass == null || ignorePrivateMethods && recordClass.hasModifierProperty(PsiModifier.PRIVATE)) return;
      boolean reportAssignment = !ContainerUtil.or(recordClass.getConstructors(), c -> JavaPsiRecordUtil.isExplicitCanonicalConstructor(c));
      for (PsiRecordComponent component : recordHeader.getRecordComponents()) {
        final PsiType type = component.getType();
        if (ClassUtils.isImmutable(type) || Mutability.getMutability(component).isUnmodifiable()) continue;
        final boolean mutable = type instanceof PsiArrayType ||
                                ContainerUtil.exists(MUTABLE_TYPES, typeName -> InheritanceUtil.isInheritor(type, typeName));
        if (!mutable) continue;
        final PsiMethod accessor = JavaPsiRecordUtil.getAccessorForRecordComponent(component);
        final boolean reportReturn = accessor == null || accessor instanceof SyntheticElement;
        if (!reportAssignment && !reportReturn) continue;
        final PsiIdentifier identifier = component.getNameIdentifier();
        if (identifier == null) continue;
        registerError(identifier, component, reportAssignment, reportReturn);
      }
    }
  }
}
