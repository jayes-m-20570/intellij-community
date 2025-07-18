// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.debugger.engine

import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.impl.DebuggerContextImpl
import com.intellij.debugger.settings.DebuggerSettings
import com.intellij.debugger.ui.breakpoints.FilteredRequestor
import com.intellij.openapi.progress.runBlockingCancellable
import com.sun.jdi.event.LocatableEvent
import com.sun.jdi.request.EventRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.withTimeout
import org.jetbrains.annotations.ApiStatus
import kotlin.time.Duration

/**
 * Find some suspend context in which evaluation is possible.
 * Then evaluate the given action using the found context.
 *
 * Throws [kotlinx.coroutines.TimeoutCancellationException] if fails to get proper context in the given amount of [timeToSuspend].
 */
@ApiStatus.Experimental
internal suspend fun <R> suspendAllAndEvaluate(
  context: DebuggerContextImpl,
  timeToSuspend: Duration,
  action: suspend (SuspendContextImpl) -> R
): R {
  val process = context.debugProcess!!
  val suspendContext = context.suspendContext
  return if (suspendContext == null) {
    // Not suspended at all.
    tryToBreakOnAnyMethodAndEvaluate(context, process, null, timeToSuspend, action)
  }
  else if (process.isEvaluationPossible(suspendContext)) {
    if (suspendContext.suspendPolicy == EventRequest.SUSPEND_EVENT_THREAD) {
      // We are on Suspend Thread breakpoint, suspend all threads first, then evaluate.
      tryToBreakOnAnyMethodAndEvaluate(context,  process, null, timeToSuspend, action)
    } else {
      // We are on a Suspend All breakpoint, we can evaluate right here.
      val result = Channel<R>(capacity = 1)

      // We have to evaluate inside SuspendContextCommandImpl, so we just start a new command.
      // TODO: are there any better ways to do this? Should we create proper command above?
      executeOnDMT(suspendContext) {
        result.send(action(suspendContext))
      }

      result.receive()
    }
  }
  else {
    // We are on a pause, cannot evaluate.
    tryToBreakOnAnyMethodAndEvaluate(context, process, suspendContext, timeToSuspend, action)
  }
}

private suspend fun <R> tryToBreakOnAnyMethodAndEvaluate (
  context: DebuggerContextImpl,
  process: DebugProcessImpl,
  pauseSuspendContext: SuspendContextImpl?,
  timeToSuspend: Duration,
  actionToEvaluate: suspend (SuspendContextImpl) -> R
): R {
  val onPause = pauseSuspendContext != null

  var timedOut = false

  val programSuspendedActionStarted = CompletableDeferred<Unit>()
  val actionResult = CompletableDeferred<R>()

  // Create a request which suspends all the threads and gets the suspendContext.
  val requestor = object : FilteredRequestor {
    override fun processLocatableEvent(action: SuspendContextCommandImpl, event: LocatableEvent?): Boolean {
      process.requestsManager.deleteRequest(this)
      if (!timedOut) {
        val suspendContext = action.suspendContext!!
        programSuspendedActionStarted.complete(Unit)
        actionResult.completeWith(runCatching {
          runBlockingCancellable {
            actionToEvaluate(suspendContext)
          }
        })
      }
      // Note: in case the context was not originally suspended, return false,
      // so that suspendContext is resumed when action is computed,
      // thus no suspension will be visible in the UI
      return onPause
    }

    override fun getSuspendPolicy(): String = DebuggerSettings.SUSPEND_ALL
  }

  val request = process.requestsManager.createMethodEntryRequest(requestor)
  request.setSuspendPolicy(EventRequest.SUSPEND_ALL)
  request.isEnabled = true

  // If the context was on pause, it should be resume first to hit the breakpoint
  if (onPause) {
    context.managerThread!!
      .invokeNow(process.createResumeCommand(pauseSuspendContext))
  }

  // Check that we hit the breakpoint within the specified timeout
  try {
    withTimeout(timeToSuspend) {
      programSuspendedActionStarted.await()
    }
  }
  catch (e: TimeoutCancellationException) {
    // Try to make it earlier.
    process.requestsManager.deleteRequest(requestor)

    withDebugContext(context.managerThread!!) {
      // FIXME: unify all this logic with evaluatable Pause
      timedOut = true
      if (programSuspendedActionStarted.isCompleted) {
        // Request was already processed, we need to ignore the timeout.
      } else {
        if (onPause) {
          // FIXME: get preferred thread from pauseSuspendContext
          // If the context was originally on pause, but after resume did not hit a breakpoint within a timeout,
          // then it should be paused again
          process.createPauseCommand(null).invokeCommand()
        }
        throw e
      }
    }
  }
  finally {
    process.requestsManager.deleteRequest(requestor)
  }

  return actionResult.await()
}
