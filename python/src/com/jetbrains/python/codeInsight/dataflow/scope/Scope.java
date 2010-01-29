package com.jetbrains.python.codeInsight.dataflow.scope;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * @author oleg
 */
public interface Scope {
    /*
   * @return defined scope local/instance/class variables and parameters, using reaching defs
   */
  Collection<ScopeVariable> getDeclaredVariables(@NotNull PsiElement anchorElement);


  /*
   * @return defined scope local/instance/class variables and parameters, using reaching defs
   */
  @Nullable
  ScopeVariable getDeclaredVariable(@NotNull PsiElement anchorElement, @NotNull String name);
}
