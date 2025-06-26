// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ex.ToolWindowManagerEx
import com.intellij.openapi.wm.impl.ToolWindowManagerImpl
import kotlin.random.Random

/**
 * Action that shuffles the order of tool window buttons in the sidebar.
 * When executed, randomly reorders all visible tool window icons in the stripe.
 */
class ShuffleToolWindowsAction : AnAction("Shuffle Tool Windows", "Randomly reorder tool window buttons", AllIcons.Actions.Refresh), DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabledAndVisible = project != null && hasMultipleToolWindows(project)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val toolWindowManager = ToolWindowManagerEx.getInstanceEx(project) as? ToolWindowManagerImpl ?: return
    
    shuffleToolWindows(toolWindowManager)
  }

  /**
   * Checks if there are multiple tool windows that can be shuffled
   */
  private fun hasMultipleToolWindows(project: com.intellij.openapi.project.Project): Boolean {
    val toolWindowManager = ToolWindowManagerEx.getInstanceEx(project)
    val toolWindowIds = toolWindowManager.toolWindowIds
    return toolWindowIds.filter { id -> 
      val toolWindow = toolWindowManager.getToolWindow(id)
      toolWindow != null && toolWindow.isShowStripeButton
    }.size > 1
  }

  /**
   * Performs the actual shuffling of tool windows by randomly reassigning their order values
   */
  private fun shuffleToolWindows(toolWindowManager: ToolWindowManagerImpl) {
    val layout = toolWindowManager.layout
    val toolWindowIds = toolWindowManager.toolWindowIds.toList()
    
    // Group tool windows by anchor and split state to maintain proper grouping
    val toolWindowsByLocation = toolWindowIds.mapNotNull { id ->
      val info = layout.getInfo(id)
      val toolWindow = toolWindowManager.getToolWindow(id)
      if (info != null && toolWindow != null && info.isShowStripeButton) {
        Triple(id, info, "${info.anchor}_${info.isSplit}")
      } else {
        null
      }
    }.groupBy { it.third }

    // Shuffle each group independently and reassign orders
    toolWindowsByLocation.forEach { (_, toolWindowsInGroup) ->
      if (toolWindowsInGroup.size > 1) {
        val shuffledIds = toolWindowsInGroup.map { it.first }.shuffled(Random.Default)
        val originalOrders = toolWindowsInGroup.map { it.second.order }.sorted()
        
        shuffledIds.forEachIndexed { index, id ->
          val newOrder = originalOrders.getOrNull(index) ?: index
          val currentInfo = layout.getInfo(id)
          if (currentInfo != null && currentInfo.order != newOrder) {
            // Use the public method to ensure proper state management
            toolWindowManager.setToolWindowAnchor(id, currentInfo.anchor, newOrder)
          }
        }
      }
    }
    
    // Force update of the tool window panes to reflect changes
    try {
      toolWindowManager.project.let { project ->
        val panes = mutableSetOf<String>()
        toolWindowIds.forEach { id ->
          layout.getInfo(id)?.let { info ->
            panes.add(info.safeToolWindowPaneId)
          }
        }
        panes.forEach { paneId ->
          try {
            toolWindowManager.getToolWindowPane(paneId).validateAndRepaint()
          } catch (e: Exception) {
            // Ignore errors for non-existent panes
          }
        }
      }
    } catch (e: Exception) {
      // Fallback: don't fail if update fails
    }
  }
}