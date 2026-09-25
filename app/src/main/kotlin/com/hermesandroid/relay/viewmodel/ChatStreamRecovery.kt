package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.util.AppForegroundTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Client-side answer recovery for a sessions-endpoint chat stream that died on
 * a transport error while the server kept working (issue #166).
 *
 * Enhanced for asynchronous background execution (Telegram/Feishu style):
 * - Tolerates background network sleep and Doze mode without early give-up.
 * - Supports immediate wake-up poll via [triggerImmediatePoll] when returning to foreground.
 */
class ChatStreamRecovery(
    private val scope: CoroutineScope,
    private val fetchHistory: suspend () -> List<MessageItem>,
    private val timing: Timing = Timing(),
) {

    data class Timing(
        val pollIntervalMs: Long = 3_000L,
        val maxPollIntervalMs: Long = 15_000L,
        val recoveryWindowMs: Long = 60L * 60_000L, // 60 minutes for long background jobs
        val maxConsecutiveFetchFailures: Int = 20,
    )

    enum class GiveUpReason {
        RUN_NOT_FOUND,
        HISTORY_UNAVAILABLE,
        TIMED_OUT,
    }

    private sealed interface Anchor {
        data class Found(val index: Int) : Anchor
        data object NotEstablished : Anchor
    }

    private var job: Job? = null
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    val isActive: Boolean
        get() = job?.isActive == true

    /**
     * Wake the recovery loop immediately (e.g. when app returns to foreground).
     */
    fun triggerImmediatePoll() {
        wakeSignal.trySend(Unit)
    }

    fun start(
        pendingUserText: String,
        priorUserMessageCount: Int,
        onIntermediateHistory: (List<MessageItem>) -> Unit,
        onRecovered: (List<MessageItem>) -> Unit,
        onGaveUp: (GiveUpReason) -> Unit,
    ) {
        job?.cancel()
        val pending = pendingUserText.trim()
        val priorUsers = priorUserMessageCount.coerceAtLeast(0)
        job = scope.launch {
            var delayMs = timing.pollIntervalMs
            var elapsedMs = 0L
            var lastSignature: String? = null
            var lastSurfacedCount = -1
            var unanchoredPolls = 0
            var consecutiveFetchFailures = 0

            while (elapsedMs < timing.recoveryWindowMs) {
                // Wait for interval or immediate wake signal from foreground transition
                val woken = withTimeoutOrNull(delayMs) {
                    wakeSignal.receive()
                } != null

                if (!woken) {
                    elapsedMs += delayMs
                    delayMs = (delayMs * 15 / 10).coerceAtMost(timing.maxPollIntervalMs)
                } else {
                    // Reset backoff on explicit wake
                    delayMs = timing.pollIntervalMs
                }

                val items = try {
                    fetchHistory()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // While backgrounded, network disconnects/sleeps are expected; do not penalize failures
                    val isForeground = AppForegroundTracker.isForeground.value
                    if (isForeground) {
                        consecutiveFetchFailures++
                        if (consecutiveFetchFailures >= timing.maxConsecutiveFetchFailures) {
                            onGaveUp(GiveUpReason.HISTORY_UNAVAILABLE)
                            return@launch
                        }
                    }
                    continue
                }

                consecutiveFetchFailures = 0
                if (items.isEmpty()) continue

                when (val anchor = resolveAnchor(items, pending, priorUsers)) {
                    is Anchor.NotEstablished -> {
                        lastSignature = null
                        // Give server more opportunities (10 polls) to write session store before declaring not found
                        if (++unanchoredPolls >= 10) {
                            onGaveUp(GiveUpReason.RUN_NOT_FOUND)
                            return@launch
                        }
                    }

                    is Anchor.Found -> {
                        unanchoredPolls = 0
                        val signature = answerSignature(items, anchor.index)
                        if (signature != null && signature == lastSignature) {
                            onRecovered(items)
                            return@launch
                        }
                        lastSignature = signature

                        if (items.size != lastSurfacedCount) {
                            lastSurfacedCount = items.size
                            onIntermediateHistory(items)
                        }
                    }
                }
            }
            onGaveUp(GiveUpReason.TIMED_OUT)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun resolveAnchor(
        items: List<MessageItem>,
        pendingUserText: String,
        priorUserCount: Int,
    ): Anchor {
        val userIndices = items.indices.filter { items[it].role == "user" }
        if (userIndices.size <= priorUserCount) return Anchor.NotEstablished
        val anchorPos = userIndices[priorUserCount]
        val storedText = items[anchorPos].contentText?.trim().orEmpty()
        // Tolerant match: exact match, or either contains the other (for multi-modal prefixes)
        if (storedText != pendingUserText && !storedText.contains(pendingUserText) && !pendingUserText.contains(storedText)) {
            return Anchor.NotEstablished
        }
        return Anchor.Found(anchorPos)
    }

    private fun answerSignature(items: List<MessageItem>, anchor: Int): String? {
        val answer = items.drop(anchor + 1).lastOrNull {
            it.role == "assistant" && !it.contentText.isNullOrBlank()
        } ?: return null
        return "${items.size}|${answer.id}|${answer.contentText?.length}"
    }
}
