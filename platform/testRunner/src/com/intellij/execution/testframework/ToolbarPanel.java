/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

/*
 * User: anna
 * Date: 25-May-2007
 */
package com.intellij.execution.testframework;

import com.intellij.execution.ExecutionBundle;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.testframework.actions.ScrollToTestSourceAction;
import com.intellij.execution.testframework.actions.ShowStatisticsAction;
import com.intellij.execution.testframework.actions.TestFrameworkActions;
import com.intellij.execution.testframework.actions.TestTreeExpander;
import com.intellij.execution.testframework.autotest.AdjustAutotestDelayActionGroup;
import com.intellij.execution.testframework.export.ExportTestResultsAction;
import com.intellij.execution.testframework.ui.AbstractTestTreeBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.OccurenceNavigator;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.util.config.ToggleBooleanProperty;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;

public class ToolbarPanel extends JPanel implements OccurenceNavigator, Disposable {
  protected final TestTreeExpander myTreeExpander = new TestTreeExpander();
  protected final FailedTestsNavigator myOccurenceNavigator;
  protected final ScrollToTestSourceAction myScrollToSource;
  private final ExportTestResultsAction myExportAction;

  private final ArrayList<ToggleModelAction> myActions = new ArrayList<ToggleModelAction>();

  public ToolbarPanel(final TestConsoleProperties properties,
                      ExecutionEnvironment environment, JComponent parent) {
    super(new BorderLayout());
    final DefaultActionGroup actionGroup = new DefaultActionGroup(null, false);
    actionGroup.addAction(new ToggleBooleanProperty(ExecutionBundle.message("junit.run.hide.passed.action.name"),
                                                    ExecutionBundle.message("junit.run.hide.passed.action.description"),
                                                    AllIcons.RunConfigurations.HidePassed,
                                                    properties, TestConsoleProperties.HIDE_PASSED_TESTS));
    actionGroup.addSeparator();

   

    actionGroup.addAction(new ToggleBooleanProperty(ExecutionBundle.message("junit.runing.info.sort.alphabetically.action.name"),
                                                    ExecutionBundle.message("junit.runing.info.sort.alphabetically.action.description"),
                                                    AllIcons.ObjectBrowser.Sorted,
                                                    properties, TestConsoleProperties.SORT_ALPHABETICALLY));
    final ToggleModelAction sortByStatistics = new ToggleModelAction(ExecutionBundle.message("junit.runing.info.sort.by.statistics.action.name"),
                                                            ExecutionBundle
                                                              .message("junit.runing.info.sort.by.statistics.action.description"),
                                                            AllIcons.RunConfigurations.SortbyDuration,
                                                            properties, TestConsoleProperties.SORT_BY_DURATION) {

      private TestFrameworkRunningModel myModel;

      @Override
      protected boolean isEnabled() {
        final TestFrameworkRunningModel model = myModel;
        return model != null && !model.isRunning();
      }

      @Override
      public void setModel(TestFrameworkRunningModel model) {
        myModel = model;
      }
    };
    myActions.add(sortByStatistics);
    actionGroup.addAction(sortByStatistics);
    actionGroup.addSeparator();

    AnAction action = CommonActionsManager.getInstance().createExpandAllAction(myTreeExpander, parent);
    action.getTemplatePresentation().setDescription(ExecutionBundle.message("junit.runing.info.expand.test.action.name"));
    actionGroup.add(action);

    action = CommonActionsManager.getInstance().createCollapseAllAction(myTreeExpander, parent);
    action.getTemplatePresentation().setDescription(ExecutionBundle.message("junit.runing.info.collapse.test.action.name"));
    actionGroup.add(action);

    actionGroup.addSeparator();
    final CommonActionsManager actionsManager = CommonActionsManager.getInstance();
    myOccurenceNavigator = new FailedTestsNavigator();
    actionGroup.add(actionsManager.createPrevOccurenceAction(myOccurenceNavigator));
    actionGroup.add(actionsManager.createNextOccurenceAction(myOccurenceNavigator));

    for (ToggleModelActionProvider actionProvider : Extensions.getExtensions(ToggleModelActionProvider.EP_NAME)) {
      final ToggleModelAction toggleModelAction = actionProvider.createToggleModelAction(properties);
      myActions.add(toggleModelAction);
      actionGroup.add(toggleModelAction);
    }

    myExportAction = ExportTestResultsAction.create(properties.getExecutor().getToolWindowId(), properties.getConfiguration());
    actionGroup.addAction(myExportAction);

    final DefaultActionGroup secondaryGroup = new DefaultActionGroup();
    secondaryGroup.setPopup(true);
    secondaryGroup.getTemplatePresentation().setIcon(AllIcons.General.SecondaryGroup);
    secondaryGroup.add(new ToggleBooleanProperty(ExecutionBundle.message("junit.runing.info.track.test.action.name"),
                                                 ExecutionBundle.message("junit.runing.info.track.test.action.description"),
                                                 null, properties, TestConsoleProperties.TRACK_RUNNING_TEST));
    secondaryGroup.add(new ToggleBooleanProperty("Hide Ignored", null, null, properties,
                                                 TestConsoleProperties.HIDE_IGNORED_TEST));
    if (Registry.is("tests.view.old.statistics.panel")) {
      secondaryGroup.add(new ShowStatisticsAction(properties));
    }
    secondaryGroup.add(new ToggleBooleanProperty("Show Inline Statistics", "Toggle the visibility of the test duration in the tree",
                                                 null, properties, TestConsoleProperties.SHOW_INLINE_STATISTICS));

    secondaryGroup.addSeparator();
    secondaryGroup.add(new ToggleBooleanProperty(ExecutionBundle.message("junit.runing.info.scroll.to.stacktrace.action.name"),
                                                 ExecutionBundle.message("junit.runing.info.scroll.to.stacktrace.action.description"),
                                                 null, properties, TestConsoleProperties.SCROLL_TO_STACK_TRACE));
    secondaryGroup.add(new ToggleBooleanProperty(ExecutionBundle.message("junit.runing.info.open.source.at.exception.action.name"),
                                                 ExecutionBundle.message("junit.runing.info.open.source.at.exception.action.description"),
                                                 null, properties, TestConsoleProperties.OPEN_FAILURE_LINE));
    myScrollToSource = new ScrollToTestSourceAction(properties);
    secondaryGroup.add(myScrollToSource);

    secondaryGroup.add(new AdjustAutotestDelayActionGroup(parent));
    secondaryGroup.addSeparator();
    secondaryGroup.add(new ToggleBooleanProperty(ExecutionBundle.message("junit.runing.info.select.first.failed.action.name"),
                                                 null, null, properties, TestConsoleProperties.SELECT_FIRST_DEFECT));
    properties.appendAdditionalActions(secondaryGroup, environment, parent);
    actionGroup.add(secondaryGroup);

    add(ActionManager.getInstance().
      createActionToolbar(ActionPlaces.TESTTREE_VIEW_TOOLBAR, actionGroup, true).
      getComponent(), BorderLayout.CENTER);
  }

  public void setModel(final TestFrameworkRunningModel model) {
    TestFrameworkActions.installFilterAction(model);
    myScrollToSource.setModel(model);
    myTreeExpander.setModel(model);
    myOccurenceNavigator.setModel(model);
    myExportAction.setModel(model);
    for (ToggleModelAction action : myActions) {
      action.setModel(model);
    }
    TestFrameworkActions.addPropertyListener(TestConsoleProperties.SORT_ALPHABETICALLY, new TestFrameworkPropertyListener<Boolean>() {
      @Override
      public void onChanged(Boolean value) {
        final AbstractTestTreeBuilder builder = model.getTreeBuilder();
        if (builder != null) {
          builder.setTestsComparator(value);
        }
      }
    }, model, true); 
    TestFrameworkActions.addPropertyListener(TestConsoleProperties.SORT_BY_DURATION, new TestFrameworkPropertyListener<Boolean>() {
      @Override
      public void onChanged(Boolean value) {
        final AbstractTestTreeBuilder builder = model.getTreeBuilder();
        if (builder != null) {
          builder.setStatisticsComparator(model.getProperties(), value);
        }
      }
    }, model, true);
  }

  public boolean hasNextOccurence() {
    return myOccurenceNavigator.hasNextOccurence();
  }

  public boolean hasPreviousOccurence() {
    return myOccurenceNavigator.hasPreviousOccurence();
  }

  public OccurenceInfo goNextOccurence() {
    return myOccurenceNavigator.goNextOccurence();
  }

  public OccurenceInfo goPreviousOccurence() {
    return myOccurenceNavigator.goPreviousOccurence();
  }

  public String getNextOccurenceActionName() {
    return myOccurenceNavigator.getNextOccurenceActionName();
  }

  public String getPreviousOccurenceActionName() {
    return myOccurenceNavigator.getPreviousOccurenceActionName();
  }

  public void dispose() {
    myScrollToSource.setModel(null);
    myExportAction.setModel(null);
  }
}
