// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("RAW_RUN_BLOCKING")

package com.intellij.toolWindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class ShuffleToolWindowsActionTest : ToolWindowManagerTestCase() {

  fun testShuffleActionIsAvailable() {
    runBlocking {
      withContext(Dispatchers.EDT) {
        // Register multiple tool windows
        registerTestToolWindows()
        
        val action = ShuffleToolWindowsAction()
        val event = createActionEvent()
        
        // Update action to check if it's enabled
        action.update(event)
        
        // Should be enabled when there are multiple tool windows
        assertTrue("Shuffle action should be enabled with multiple tool windows", 
                  event.presentation.isEnabledAndVisible)
      }
    }
  }

  fun testShuffleActionDisabledWithFewToolWindows() {
    runBlocking {
      withContext(Dispatchers.EDT) {
        // Register only one tool window
        manager!!.initToolWindow(RegisterToolWindowTask(
          id = "SingleWindow",
          anchor = ToolWindowAnchor.LEFT,
          canWorkInDumbMode = true,
        ))
        
        val action = ShuffleToolWindowsAction()
        val event = createActionEvent()
        
        action.update(event)
        
        // Should be disabled when there's only one tool window
        assertFalse("Shuffle action should be disabled with single tool window", 
                   event.presentation.isEnabledAndVisible)
      }
    }
  }

  fun testShuffleChangesToolWindowOrder() {
    runBlocking {
      withContext(Dispatchers.EDT) {
        // Register multiple tool windows
        registerTestToolWindows()
        
        // Get initial order
        val initialOrder = getToolWindowOrder()
        
        // Execute shuffle action
        val action = ShuffleToolWindowsAction()
        val event = createActionEvent()
        action.actionPerformed(event)
        
        // Get new order
        val newOrder = getToolWindowOrder()
        
        // Order should have changed (with very high probability)
        // Note: There's a tiny chance they could be the same, but very unlikely
        assertTrue("Tool window order should change after shuffle", 
                  initialOrder.size == newOrder.size && initialOrder.zip(newOrder).any { it.first != it.second })
      }
    }
  }

  fun testShufflePreservesToolWindowsByAnchor() {
    runBlocking {
      withContext(Dispatchers.EDT) {
        // Register tool windows on different anchors
        manager!!.initToolWindow(RegisterToolWindowTask(
          id = "LeftWindow1",
          anchor = ToolWindowAnchor.LEFT,
          canWorkInDumbMode = true,
        ))
        manager!!.initToolWindow(RegisterToolWindowTask(
          id = "LeftWindow2", 
          anchor = ToolWindowAnchor.LEFT,
          canWorkInDumbMode = true,
        ))
        manager!!.initToolWindow(RegisterToolWindowTask(
          id = "RightWindow1",
          anchor = ToolWindowAnchor.RIGHT,
          canWorkInDumbMode = true,
        ))
        
        val action = ShuffleToolWindowsAction()
        val event = createActionEvent()
        action.actionPerformed(event)
        
        // Check that tool windows are still in correct anchors
        val layout = manager!!.layout
        assertEquals("LeftWindow1 should still be on left", ToolWindowAnchor.LEFT, layout.getInfo("LeftWindow1")?.anchor)
        assertEquals("LeftWindow2 should still be on left", ToolWindowAnchor.LEFT, layout.getInfo("LeftWindow2")?.anchor)
        assertEquals("RightWindow1 should still be on right", ToolWindowAnchor.RIGHT, layout.getInfo("RightWindow1")?.anchor)
      }
    }
  }

  private fun registerTestToolWindows() {
    // Register multiple tool windows for testing
    manager!!.initToolWindow(RegisterToolWindowTask(
      id = "TestWindow1",
      anchor = ToolWindowAnchor.LEFT,
      canWorkInDumbMode = true,
    ))
    manager!!.initToolWindow(RegisterToolWindowTask(
      id = "TestWindow2",
      anchor = ToolWindowAnchor.LEFT, 
      canWorkInDumbMode = true,
    ))
    manager!!.initToolWindow(RegisterToolWindowTask(
      id = "TestWindow3",
      anchor = ToolWindowAnchor.LEFT,
      canWorkInDumbMode = true,
    ))
  }

  private fun createActionEvent(): AnActionEvent {
    val dataContext = SimpleDataContext.getSimpleContext(CommonDataKeys.PROJECT, project)
    return AnActionEvent.createFromDataContext("", null, dataContext)
  }

  private fun getToolWindowOrder(): List<Pair<String, Int>> {
    val layout = manager!!.layout
    return manager!!.toolWindowIds.mapNotNull { id ->
      layout.getInfo(id)?.let { info -> id to info.order }
    }.sortedBy { it.second }
  }
}