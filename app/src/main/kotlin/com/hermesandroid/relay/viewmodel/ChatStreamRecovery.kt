package com.hermesandroid.relay.viewmodel

import android.util.Log
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
 * Client-side asynchronous sync engine (Telegram/Feishu architecture).
 *
 * Core principles:
 * 1. The client is a pure VIEWER; server-side tasks are autonomous and NEVER killed by client timeouts.
 * 2. NO timeouts. The engine continues syncing until the final answer is persisted.
 * 3. Network disconnects (Doze mode, screen-off, app switching) NEVER cause a give-up or error banner.
 * 4. Immediate wake-up on foreground return via [triggerImmediatePoll].
 */
class ChatStreamRecovery(
    private val scope: CoroutineScope,
    private val fetchHistory: suspend () -> List<MessageItem>,
    private val timing: Timing = Timing(),
) {

    data class Timing(
        val pollIntervalMs: Long = 3_000L,
        val maxPollIntervalMs: Long = 15_000L,
        val backgroundPollIntervalMs: Long = 20_000L,
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
     * Wake the sync loop immediately (e.g. when app returns to foreground).
     */
    fun triggerImmediatePoll() {
        wakeSignal.trySend(Unit)
    }

    fun start(
        pendingUserText: String,
        priorUserMessageCount: Int,
        onIntermediateHistory: (List<MessageItem>) -> Unit,
        onRecovered: (List<MessageItem>) -> Unit,
        onGaveUp: (GiveUpReason) -> Unit = {},
    ) {
        job?.cancel()
        val pending = pendingUserText.trim()
        val priorUsers = priorUserMessageCount.coerceAtLeast(0)
        job = scope.launch {
            var delayMs = timing.pollIntervalMs
            var lastSignature: String? = null
            var lastSurfacedCount = -1

            // Infinite sync loop: client never prematurely gives up on server tasks
            while (true) {
                val isForeground = AppForegroundTracker.isForeground.value
                val currentTargetDelay = if (isForeground) delayMs else timing.backgroundPollIntervalMs

                // Wait for interval or immediate wake signal when coming back to foreground
                val woken = withTimeoutOrNull(currentTargetDelay) {
                    wakeSignal.receive()
                } != null

                if (woken) {
                    delayMs = timing.pollIntervalMs
                } else if (isForeground) {
                    delayMs = (delayMs * 15 / 10).coerceAtMost(timing.maxPollIntervalMs)
                }

                val items = try {
                    fetchHistory()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d("ChatStreamRecovery", "Background sync transient network glitch: ${e.message}")
                    continue
                }

                if (items.isEmpty()) continue

                when (val anchor = resolveAnchor(items, pending, priorUsers)) {
                    is Anchor.NotEstablished -> {
                        lastSignature = null
                        // Server is still processing or preparing; continue polling without giving up
                        continue
                    }

                    is Anchor.Found -> {
                        val signature = answerSignature(items, anchor.index)
                        if (signature != null && signature == lastSignature) {
                            // Stable final assistant answer reached! Complete!
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
        // Tolerant match: exact match, or substring match (for multi-modal / voice prefixes)
        if (storedText != pendingUserText && !storedText.contains(pendingUserText) && !pendingUserText.contains(storedText)) {
            // Even if text differs slightly (e.g. prompt rewrite), if index matches positional turn, accept it
            if (userIndices.size == priorUserCount + 1) {
                return Anchor.Found(anchorPos)
            }
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
