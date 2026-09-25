package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.ui.UiMessageBus
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.upstream.ServerCapabilities
import com.hermesandroid.relay.ui.theme.RelayRefresh

enum class ChatTransportTier(val endpointId: String, val label: String) {
    Gateway("gateway", "⚡ Gateway"),
    Sessions("sessions", "Direct API"),
    Completions("completions", "Direct API"),
    Runs("runs", "Direct API"),
    Offline("offline", "offline"),
}

enum class ChatTransportTone {
    Active,
    Fallback,
    Unavailable,
}

data class ChatTransportStatus(
    val tier: ChatTransportTier,
    val tone: ChatTransportTone,
    val reason: String,
    val detail: String,
) {
    val available: Boolean
        get() = tone != ChatTransportTone.Unavailable && tier != ChatTransportTier.Offline
}

fun resolveChatTransportStatus(
    streamingEndpoint: String,
    gatewayAvailability: GatewayAvailability,
    serverCapabilities: ServerCapabilities,
): ChatTransportStatus {
    val preference = streamingEndpoint.trim().lowercase()
    val gatewayReady = gatewayAvailability == GatewayAvailability.Ready

    fun unavailable(tier: ChatTransportTier, reason: String): ChatTransportStatus =
        ChatTransportStatus(
            tier = tier,
            tone = ChatTransportTone.Unavailable,
            reason = reason,
            detail = "${tier.label}: unavailable on the current connection.",
        )

    fun offline(reason: String = "offline"): ChatTransportStatus =
        ChatTransportStatus(
            tier = ChatTransportTier.Offline,
            tone = ChatTransportTone.Unavailable,
            reason = reason,
            detail = "No reachable Hermes chat transport is available.",
        )

    fun manualSse(tier: ChatTransportTier, supported: Boolean): ChatTransportStatus {
        if (!serverCapabilities.healthy) return offline()
        return if (supported) {
            ChatTransportStatus(
                tier = tier,
                tone = ChatTransportTone.Active,
                reason = "${tier.plainName()} selected",
                detail = tier.detailText(),
            )
        } else {
            unavailable(tier, "${tier.plainName()} unavailable")
        }
    }

    return when (preference) {
        "auto", "gateway" -> when {
            gatewayReady -> ChatTransportStatus(
                tier = ChatTransportTier.Gateway,
                tone = ChatTransportTone.Active,
                reason = "Gateway connected",
                detail = ChatTransportTier.Gateway.detailText(),
            )
            serverCapabilities.sessionsChatStream -> manualSse(ChatTransportTier.Sessions, true)
            serverCapabilities.portable -> manualSse(ChatTransportTier.Completions, true)
            else -> unavailable(
                ChatTransportTier.Gateway,
                gatewayFallbackReason(gatewayAvailability),
            )
        }
        "sessions" -> manualSse(ChatTransportTier.Sessions, serverCapabilities.sessionsChatStream)
        "completions" -> manualSse(ChatTransportTier.Completions, serverCapabilities.portable)
        "runs" -> manualSse(ChatTransportTier.Runs, serverCapabilities.runs)
        else -> offline()
    }
}

private fun gatewayFallbackReason(availability: GatewayAvailability): String =
    when (availability) {
        GatewayAvailability.SignInRequired -> "网关需要登录"
        GatewayAvailability.Unreachable -> "网关不可用"
        GatewayAvailability.Unsupported -> "网关不受支持"
        GatewayAvailability.Unknown -> "正在检查网关"
        GatewayAvailability.Ready -> "网关就绪"
    }

private fun ChatTransportTier.plainName(): String =
    when (this) {
        ChatTransportTier.Gateway -> "网关"
        ChatTransportTier.Sessions -> "Direct API"
        ChatTransportTier.Completions -> "Direct API"
        ChatTransportTier.Runs -> "Direct API"
        ChatTransportTier.Offline -> "离线"
    }

private fun ChatTransportTier.detailText(): String =
    when (this) {
        ChatTransportTier.Gateway ->
            "Hermes 聊天使用已登录的控制台连接。"
        ChatTransportTier.Sessions ->
            "Direct API 直连兼容聊天（带服务端会话历史）。"
        ChatTransportTier.Completions ->
            "Direct API 直连兼容聊天。"
        ChatTransportTier.Runs ->
            "Direct API 直连兼容聊天（带流式运行事件）。"
        ChatTransportTier.Offline ->
            "没有可达的聊天传输通道。"
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatTransportStatusBadge(
    status: ChatTransportStatus,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val textColor = status.textColor()
    val background = status.backgroundColor()
    Surface(
        modifier = modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = {
                UiMessageBus.info("${status.reason}: ${status.detail}")
            },
        ),
        shape = RoundedCornerShape(999.dp),
        color = background,
        contentColor = textColor,
    ) {
        Text(
            text = status.tier.label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = textColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun ChatTransportStatusBadge(
    streamingEndpoint: String,
    gatewayAvailability: GatewayAvailability,
    serverCapabilities: ServerCapabilities,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val status = remember(streamingEndpoint, gatewayAvailability, serverCapabilities) {
        resolveChatTransportStatus(
            streamingEndpoint = streamingEndpoint,
            gatewayAvailability = gatewayAvailability,
            serverCapabilities = serverCapabilities,
        )
    }
    ChatTransportStatusBadge(
        status = status,
        modifier = modifier,
        onClick = onClick,
    )
}

@Composable
fun ChatTransportStatus.textColor(): Color =
    when (tone) {
        ChatTransportTone.Active -> RelayRefresh.Green
        ChatTransportTone.Fallback -> RelayRefresh.Amber
        ChatTransportTone.Unavailable -> RelayRefresh.Muted
    }

@Composable
private fun ChatTransportStatus.backgroundColor(): Color =
    when (tone) {
        ChatTransportTone.Active -> RelayRefresh.Green.copy(alpha = 0.12f)
        ChatTransportTone.Fallback -> RelayRefresh.Amber.copy(alpha = 0.14f)
        ChatTransportTone.Unavailable -> RelayRefresh.Navy3.copy(alpha = 0.72f)
    }
