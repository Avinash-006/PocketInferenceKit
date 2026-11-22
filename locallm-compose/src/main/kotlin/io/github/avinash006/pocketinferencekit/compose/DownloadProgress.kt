package io.github.avinash006.pocketinferencekit.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.avinash006.pocketinferencekit.models.DownloadState

/**
 * Displays the selected model's [state] with an accessible progress bar for byte-bearing states.
 * Use [modifier], [textStyle], and [indicatorColor] to match the host app's theme.
 */
@Composable
public fun DownloadProgress(
    state: DownloadState,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    indicatorColor: Color = Color(0xFF17684F),
) {
    val (label, fraction) =
        when (state) {
            DownloadState.Idle -> return
            DownloadState.Completed -> "Installed" to null
            DownloadState.Cancelled -> "Download cancelled" to null
            is DownloadState.Failed -> (state.exception.message ?: "Download failed") to null
            is DownloadState.Running -> "Downloading" to fraction(state.receivedBytes, state.sizeBytes)
            is DownloadState.Waiting -> "Waiting to download" to fraction(state.receivedBytes, state.sizeBytes)
            is DownloadState.Paused -> "Paused" to fraction(state.receivedBytes, state.sizeBytes)
        }
    Column(modifier) {
        BasicText(
            if (fraction == null) label else "$label · ${(fraction * 100).toInt()}%",
            style = textStyle,
        )
        if (fraction != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(indicatorColor.copy(alpha = 0.24f))
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
            ) {
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(indicatorColor))
            }
        }
    }
}

private fun fraction(
    received: Long,
    total: Long,
): Float = if (total <= 0) 0f else (received.toDouble() / total).toFloat().coerceIn(0f, 1f)
