package com.sharkking.assistant.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 轻提示的状态容器。
 *
 * 不用系统 Toast：MIUI 在通知权限关闭时会连带屏蔽 Toast，提示可能根本不显示；
 * 且 Toast 固定弹在屏幕底部，会压住全屏游戏画面。
 */
class TipState {
    internal val text = mutableStateOf<String?>(null)

    /** 每次显示都换 key，连点同一个开关时能重新触发动画与计时 */
    internal val seq = mutableStateOf(0)

    fun show(message: String) {
        text.value = message
        seq.value++
    }
}

@Composable
fun rememberTipState(): TipState = remember { TipState() }

/**
 * 浮在内容上方的胶囊提示，[durationMs] 后自动淡出。
 *
 * 放在 Box 里盖住页面即可，自身不拦截触摸（只占顶部一条，且无点击处理）。
 */
@Composable
fun TipHost(
    state: TipState,
    modifier: Modifier = Modifier,
    durationMs: Long = 1600,
) {
    val message = state.text.value
    val seq = state.seq.value
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(seq) {
        if (seq == 0 || message == null) return@LaunchedEffect
        visible = true
        delay(durationMs)
        visible = false
        // 等淡出动画走完再清空文本，否则文字会在动画中途消失
        delay(220)
        if (state.seq.value == seq) state.text.value = null
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) 140 else 220),
        label = "tipAlpha",
    )

    if (message == null || alpha == 0f) return

    Box(modifier.fillMaxWidth().padding(top = 8.dp), Alignment.TopCenter) {
        Surface(
            modifier = Modifier.alpha(alpha),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.inverseSurface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Text(
                message,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
    }
}
