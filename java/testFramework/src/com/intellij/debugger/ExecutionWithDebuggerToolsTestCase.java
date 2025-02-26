/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.debugger;

import com.intellij.debugger.engine.*;
import com.intellij.debugger.engine.evaluation.CodeFragmentKind;
import com.intellij.debugger.engine.evaluation.EvaluateException;
import com.intellij.debugger.engine.evaluation.TextWithImportsImpl;
import com.intellij.debugger.engine.events.DebuggerCommandImpl;
import com.intellij.debugger.engine.events.SuspendContextCommandImpl;
import com.intellij.debugger.impl.DebuggerManagerImpl;
import com.intellij.debugger.impl.PositionUtil;
import com.intellij.debugger.impl.PrioritizedTask;
import com.intellij.debugger.impl.SynchronizationBasedSemaphore;
import com.intellij.debugger.jdi.StackFrameProxyImpl;
import com.intellij.debugger.ui.breakpoints.Breakpoint;
import com.intellij.debugger.ui.breakpoints.BreakpointManager;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionTestCase;
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl;
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.CompositeException;
import com.intellij.ui.classFilter.ClassFilter;
import com.intellij.util.IJSwingUtilities;
import com.intellij.util.TimeoutUtil;
import com.intellij.util.ui.UIUtil;
import com.sun.jdi.Method;
import com.sun.jdi.ThreadReference;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

public abstract class ExecutionWithDebuggerToolsTestCase extends ExecutionTestCase {
  private DebugProcessListener myPauseScriptListener = null;
  private final List<SuspendContextRunnable> myScriptRunnables = new ArrayList<SuspendContextRunnable>();
  private final SynchronizationBasedSemaphore myScriptRunnablesSema = new SynchronizationBasedSemaphore();
  protected static final int RATHER_LATER_INVOKES_N = 10;
  public DebugProcessImpl myDebugProcess = null;
  private final CompositeException myException = new CompositeException();

  private static class InvokeRatherLaterRequest {
    private final DebuggerCommandImpl myDebuggerCommand;
    private final DebugProcessImpl myDebugProcess;
    int invokesN = 0;

    public InvokeRatherLaterRequest(DebuggerCommandImpl debuggerCommand, DebugProcessImpl debugProcess) {
      myDebuggerCommand = debuggerCommand;
      myDebugProcess = debugProcess;
    }
  }

  public final List<InvokeRatherLaterRequest> myRatherLaterRequests = new ArrayList<InvokeRatherLaterRequest>();

  protected DebugProcessImpl getDebugProcess() {
    return myDebugProcess;
  }

  protected String readValue(String comment, String valueName) {
    int valueStart = comment.indexOf(valueName);
    if (valueStart == -1) return null;

    int valueEnd = comment.indexOf(')', valueStart);
    return comment.substring(valueStart + valueName.length() + 1, valueEnd);
  }

  protected void resume(SuspendContextImpl context) {
    DebugProcessImpl debugProcess = context.getDebugProcess();
    debugProcess.getManagerThread().schedule(debugProcess.createResumeCommand(context, PrioritizedTask.Priority.LOW));
  }

  protected void waitBreakpoints() {
    myScriptRunnablesSema.down();
    waitFor(new Runnable() {
      @Override
      public void run() {
        myScriptRunnablesSema.waitFor();
      }
    });
  }

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    synchronized (myException) {
      if (!myException.isEmpty()) throw myException;
    }
  }

  protected void onBreakpoint(SuspendContextRunnable runnable) {
    if (myPauseScriptListener == null) {
      final DebugProcessImpl debugProcess = getDebugProcess();
      
      assertTrue("Debug process was not started", debugProcess != null);
      
      myPauseScriptListener = new DelayedEventsProcessListener(
        new DebugProcessAdapterImpl() {
          @Override
          public void paused(SuspendContextImpl suspendContext) {
            try {
              if (myScriptRunnables.isEmpty()) {
                print("resuming ", ProcessOutputTypes.SYSTEM);
                printContext(suspendContext);
                resume(suspendContext);
                return;
              }
              SuspendContextRunnable suspendContextRunnable = myScriptRunnables.remove(0);
              suspendContextRunnable.run(suspendContext);
            }
            catch (Exception e) {
              addException(e);
              error(e);
            }
            catch (AssertionError e) {
              addException(e);
              paused(suspendContext);
            }

            if (myScriptRunnables.isEmpty()) {
              myScriptRunnablesSema.up();
            }
          }

          //executed in manager thread
          @Override
          public void resumed(SuspendContextImpl suspendContext) {
            final SuspendContextImpl pausedContext = debugProcess.getSuspendManager().getPausedContext();
            if (pausedContext != null) {
              debugProcess.getManagerThread().schedule(new SuspendContextCommandImpl(pausedContext) {
                @Override
                public void contextAction() throws Exception {
                  paused(pausedContext);
                }
              });
            }
          }
        }
      );
      debugProcess.addDebugProcessListener(myPauseScriptListener);
    }
    myScriptRunnables.add(runnable);
  }

  protected void printFrameProxy(StackFrameProxyImpl frameProxy) throws EvaluateException {
    int frameIndex = frameProxy.getFrameIndex();
    Method method = frameProxy.location().method();

    println("frameProxy(" + frameIndex + ") = " + method, ProcessOutputTypes.SYSTEM);
  }

  protected void printContext(final StackFrameContext context) {
    ApplicationManager.getApplication().runReadAction(new Runnable() {
      @Override
      public void run() {
        if (context.getFrameProxy() != null) {
          SourcePosition sourcePosition = PositionUtil.getSourcePosition(context);
          println(sourcePosition.getFile().getVirtualFile().getName() + ":" + sourcePosition.getLine(), ProcessOutputTypes.SYSTEM);
        }
        else {
          println("Context thread is null", ProcessOutputTypes.SYSTEM);
        }
      }
    });
  }

  protected void printContextWithText(final StackFrameContext context) {
    ApplicationManager.getApplication().runReadAction(new Runnable() {
      @Override
      public void run() {
        if (context.getFrameProxy() != null) {
          SourcePosition sourcePosition = PositionUtil.getSourcePosition(context);
          int offset = sourcePosition.getOffset();
          Document document = PsiDocumentManager.getInstance(myProject).getDocument(sourcePosition.getFile());
          CharSequence text = document.getImmutableCharSequence();
          String positionText = "";
          if (offset > -1) {
            positionText = StringUtil.escapeLineBreak(" [" + text.subSequence(Math.max(0, offset - 20), offset) + "<*>"
            + text.subSequence(offset, Math.min(offset + 20, text.length())) + "]");
          }

          println(sourcePosition.getFile().getVirtualFile().getName()
                  + ":" + sourcePosition.getLine()
                  + positionText,
                  ProcessOutputTypes.SYSTEM);
        }
        else {
          println("Context thread is null", ProcessOutputTypes.SYSTEM);
        }
      }
    });
  }

  protected void invokeRatherLater(SuspendContextImpl context, final Runnable runnable) {
    invokeRatherLater(new SuspendContextCommandImpl(context) {
      @Override
      public void contextAction() throws Exception {
        DebuggerInvocationUtil.invokeLater(myProject, runnable);
      }
    });
  }

  protected void pumpSwingThread() {
    LOG.assertTrue(SwingUtilities.isEventDispatchThread());

    final InvokeRatherLaterRequest request = myRatherLaterRequests.get(0);
    request.invokesN++;

    if (request.invokesN == RATHER_LATER_INVOKES_N) {
      myRatherLaterRequests.remove(0);
      if (!myRatherLaterRequests.isEmpty()) pumpSwingThread();
    }

    if (request.myDebuggerCommand instanceof SuspendContextCommandImpl) {
      request.myDebugProcess.getManagerThread().schedule(new SuspendContextCommandImpl(
          ((SuspendContextCommandImpl)request.myDebuggerCommand).getSuspendContext()) {
          @Override
          public void contextAction() throws Exception {
            pumpDebuggerThread(request);
          }

          @Override
          protected void commandCancelled() {
            pumpDebuggerThread(request);
          }
        });
    }
    else {
      request.myDebugProcess.getManagerThread().schedule(new DebuggerCommandImpl() {
          @Override
          protected void action() throws Exception {
            pumpDebuggerThread(request);
          }

          @Override
          protected void commandCancelled() {
            pumpDebuggerThread(request);
          }
        });
    }
  }

  private void pumpDebuggerThread(final InvokeRatherLaterRequest request) {
    if (request.invokesN == RATHER_LATER_INVOKES_N) {
      request.myDebugProcess.getManagerThread().schedule(request.myDebuggerCommand);
    }
    else {
      if (!SwingUtilities.isEventDispatchThread()) {
        UIUtil.invokeAndWaitIfNeeded(new Runnable() {
          @Override
          public void run() {
            pumpSwingThread();
          }
        });
      }
      else {
        SwingUtilities.invokeLater(new Runnable() {
          @Override
          public void run() {
            pumpSwingThread();
          }
        });
      }
    }
  }

  protected void invokeRatherLater(final DebuggerCommandImpl command) {
    IJSwingUtilities.invoke(new Runnable() {
      @Override
      public void run() {
        InvokeRatherLaterRequest request = new InvokeRatherLaterRequest(command, getDebugProcess());
        myRatherLaterRequests.add(request);

        if (myRatherLaterRequests.size() == 1) pumpSwingThread();
      }
    });
  }

  protected void addException(Throwable e) {
    synchronized (myException) {
      myException.add(e);
    }
  }

  protected void error(Throwable th) {
    fail(StringUtil.getThrowableText(th));
  }

  public void createBreakpoints(final PsiFile file) {
    Runnable runnable = new Runnable() {
      @Override
      public void run() {
        BreakpointManager breakpointManager = DebuggerManagerImpl.getInstanceEx(myProject).getBreakpointManager();
        Document document = PsiDocumentManager.getInstance(myProject).getDocument(file);
        int offset = -1;
        for (; ;) {
          offset = document.getText().indexOf("Breakpoint!", offset + 1);
          if (offset == -1) break;

          int commentLine = document.getLineNumber(offset);

          String comment = document.getText().substring(document.getLineStartOffset(commentLine), document.getLineEndOffset(commentLine));

          Breakpoint breakpoint;

          if (comment.indexOf("Method") != -1) {
            breakpoint = breakpointManager.addMethodBreakpoint(document, commentLine + 1);
            if (breakpoint != null) {
              println("MethodBreakpoint created at " + file.getVirtualFile().getName() + ":" + (commentLine + 2),
                      ProcessOutputTypes.SYSTEM);
            }
          }
          else if (comment.indexOf("Field") != -1) {
            breakpoint = breakpointManager.addFieldBreakpoint(document, commentLine + 1, readValue(comment, "Field"));
            if (breakpoint != null) {
              println("FieldBreakpoint created at " + file.getVirtualFile().getName() + ":" + (commentLine + 2), ProcessOutputTypes.SYSTEM);
            }
          }
          else if (comment.indexOf("Exception") != -1) {
            breakpoint = breakpointManager.addExceptionBreakpoint(readValue(comment, "Exception"), "");
            if (breakpoint != null) {
              println("ExceptionBreakpoint created at " + file.getVirtualFile().getName() + ":" + (commentLine + 2),
                      ProcessOutputTypes.SYSTEM);
            }
          }
          else {
            breakpoint = breakpointManager.addLineBreakpoint(document, commentLine + 1);
            if (breakpoint != null) {
              println("LineBreakpoint created at " + file.getVirtualFile().getName() + ":" + (commentLine + 2), ProcessOutputTypes.SYSTEM);
            }
          }

          String suspendPolicy = readValue(comment, "suspendPolicy");
          if (suspendPolicy != null) {
            //breakpoint.setSuspend(!DebuggerSettings.SUSPEND_NONE.equals(suspendPolicy));
            breakpoint.setSuspendPolicy(suspendPolicy);
            println("SUSPEND_POLICY = " + suspendPolicy, ProcessOutputTypes.SYSTEM);
          }
          String condition = readValue(comment, "Condition");
          if (condition != null) {
            //breakpoint.CONDITION_ENABLED = true;
            breakpoint.setCondition(new TextWithImportsImpl(CodeFragmentKind.EXPRESSION, condition));
            println("Condition = " + condition, ProcessOutputTypes.SYSTEM);
          }
          String passCount = readValue(comment, "Pass count");
          if (passCount != null) {
            breakpoint.setCountFilterEnabled(true);
            breakpoint.setCountFilter(Integer.parseInt(passCount));
            println("Pass count = " + passCount, ProcessOutputTypes.SYSTEM);
          }

          String classFilters = readValue(comment, "Class filters");
          if (classFilters != null) {
            breakpoint.setClassFiltersEnabled(true);
            StringTokenizer tokenizer = new StringTokenizer(classFilters, " ,");
            ArrayList<ClassFilter> lst = new ArrayList<ClassFilter>();

            while (tokenizer.hasMoreTokens()) {
              ClassFilter filter = new ClassFilter();
              filter.setEnabled(true);
              filter.setPattern(tokenizer.nextToken());
              lst.add(filter);
            }

            breakpoint.setClassFilters(lst.toArray(new ClassFilter[lst.size()]));
            println("Class filters = " + classFilters, ProcessOutputTypes.SYSTEM);
          }
        }
      }
    };
    if (!SwingUtilities.isEventDispatchThread()) {
      DebuggerInvocationUtil.invokeAndWait(myProject, runnable, ModalityState.defaultModalityState());
    }
    else {
      runnable.run();
    }
  }

  private Sdk getTestJdk() {
    try {
      ProjectJdkImpl jdk = (ProjectJdkImpl)JavaAwareProjectJdkTableImpl.getInstanceEx().getInternalJdk().clone();
      jdk.setName("JDK");
      return jdk;
    }
    catch (CloneNotSupportedException e) {
      LOG.error(e);
      return null;
    }
  }

  protected void setTestJDK() {
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        Sdk jdk = ProjectJdkTable.getInstance().findJdk("JDK");
        if (jdk != null) {
          ProjectJdkTable.getInstance().removeJdk(jdk);
        }

        ProjectJdkTable.getInstance().addJdk(getTestJdk());
      }
    });
  }

  private class DelayedEventsProcessListener implements DebugProcessListener {
    private final DebugProcessAdapterImpl myTarget;

    public DelayedEventsProcessListener(DebugProcessAdapterImpl target) {
      myTarget = target;
    }

    @Override
    public void threadStarted(DebugProcess proc, ThreadReference thread) {
    }

    @Override
    public void threadStopped(DebugProcess proc, ThreadReference thread) {
    }

    @Override
    public void paused(final SuspendContext suspendContext) {
      pauseExecution();
      myTarget.paused(suspendContext);
    }

    @Override
    public void resumed(final SuspendContext suspendContext) {
      pauseExecution();
      myTarget.resumed(suspendContext);
    }

    @Override
    public void processDetached(final DebugProcess process, final boolean closedByUser) {
      myTarget.processDetached(process, closedByUser);
    }

    @Override
    public void processAttached(final DebugProcess process) {
      myTarget.processAttached(process);
    }

    @Override
    public void connectorIsReady() {
      myTarget.connectorIsReady();
    }

    @Override
    public void attachException(final RunProfileState state, final ExecutionException exception, final RemoteConnection remoteConnection) {
      myTarget.attachException(state, exception, remoteConnection);
    }

    private void pauseExecution() {
      TimeoutUtil.sleep(10);
    }
  }
}
