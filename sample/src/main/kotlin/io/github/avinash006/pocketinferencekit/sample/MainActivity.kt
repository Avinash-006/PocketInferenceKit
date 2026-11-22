package io.github.avinash006.pocketinferencekit.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.avinash006.pocketinferencekit.core.ChatMessage
import io.github.avinash006.pocketinferencekit.core.ChatRole
import io.github.avinash006.pocketinferencekit.models.DownloadState
import io.github.avinash006.pocketinferencekit.models.ModelCatalog
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFFAEE8D0), secondary = Color(0xFFB4C8F3))
            } else {
                lightColorScheme(primary = Color(0xFF17684F), secondary = Color(0xFF435982))
            }
            MaterialTheme(colorScheme = colors) { PocketDemo() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PocketDemo(model: SampleViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    BackHandler(state.chatOpen || state.loading, model::backToModels)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state.chatOpen) state.selected.displayName else "PocketInferenceKit")
                },
                navigationIcon = {
                    if (state.chatOpen) TextButton(model::backToModels) { Text("Models") }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        if (state.chatOpen) {
            ChatScreen(state, model, Modifier.padding(padding))
        } else {
            ModelPicker(state, model, Modifier.padding(padding))
        }
    }
}

@Composable
private fun ModelPicker(state: SampleState, model: SampleViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val installed = state.selected.sha256 in state.installed || state.download is DownloadState.Completed
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("On-device models", style = MaterialTheme.typography.headlineSmall) }
        items(ModelCatalog.entries, key = { it.id }) { spec ->
            val selected = spec.id == state.selected.id
            OutlinedCard(onClick = { model.select(spec) }, enabled = !state.loading) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(spec.displayName, style = MaterialTheme.typography.titleMedium)
                    val fileSize = android.text.format.Formatter.formatFileSize(context, spec.sizeBytes)
                    val ram = spec.minRamMb?.let { "$it MiB RAM" } ?: "RAM requirement varies by device"
                    Text("$fileSize · $ram", style = MaterialTheme.typography.bodyMedium)
                    if (spec.sha256 in state.installed) Text("Installed", color = MaterialTheme.colorScheme.primary)
                    if (selected) Text("Selected", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Download over Wi-Fi only")
                Switch(
                    checked = state.wifiOnly,
                    enabled = state.download !is DownloadState.Running && state.download !is DownloadState.Waiting,
                    onCheckedChange = model::setWifiOnly,
                )
            }
        }
        item {
            when (val status = state.download) {
                is DownloadState.Running -> {
                    TransferProgress("Downloading", status.receivedBytes, status.sizeBytes)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(model::pause) { Text("Pause") }
                        TextButton(model::cancel) { Text("Cancel") }
                    }
                }
                is DownloadState.Waiting -> {
                    TransferProgress("Waiting for a network or background time", status.receivedBytes, status.sizeBytes)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(model::pause) { Text("Pause") }
                        TextButton(model::cancel) { Text("Cancel") }
                    }
                }
                is DownloadState.Paused -> {
                    TransferProgress("Paused", status.receivedBytes, status.sizeBytes)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(model::resume) { Text("Resume") }
                        TextButton(model::cancel) { Text("Cancel") }
                    }
                }
                is DownloadState.Failed -> {
                    Text(status.exception.message.orEmpty(), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(model::download) { Text("Retry download") }
                }
                DownloadState.Cancelled, DownloadState.Idle -> {
                    if (!installed) OutlinedButton(model::download) { Text("Download only") }
                }
                DownloadState.Completed -> Unit
            }
        }
        item {
            val canOpen = !state.loading && state.download !is DownloadState.Paused
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(model::openChat, enabled = canOpen) {
                    Text(
                        when {
                            state.loading -> "Preparing model…"
                            installed -> "Open chat"
                            else -> "Download and open chat"
                        },
                    )
                }
                if (installed) TextButton(model::delete, enabled = !state.loading) { Text("Remove") }
            }
        }
        state.error?.let { message ->
            item { ErrorCard(message, model::dismissError) }
        }
    }
}

@Composable
private fun TransferProgress(label: String, bytes: Long, total: Long) {
    val progress = if (total == 0L) 0f else (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label · ${(progress * 100).toInt()}%")
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ChatScreen(state: SampleState, model: SampleViewModel, modifier: Modifier = Modifier) {
    val recents = rememberLazyListState()
    val pending = state.pendingPrompt
    val messages = if (pending == null) state.messages else {
        state.messages + ChatMessage(ChatRole.User, pending) + ChatMessage(ChatRole.Assistant, state.streaming.ifEmpty { "Generating…" })
    }
    LaunchedEffect(messages.size, state.streaming.length) {
        if (messages.isNotEmpty()) recents.scrollToItem(messages.lastIndex)
    }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, enabled = false, label = { Text(state.backend?.name ?: "Backend pending") })
            val rate = state.stats?.tokensPerSecond?.let { String.format(Locale.getDefault(), "%.1f tok/s", it) }
                ?: "— tok/s"
            AssistChip(onClick = {}, enabled = false, label = { Text(rate) })
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = recents,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages) { message -> ChatBubble(message) }
        }
        state.error?.let { ErrorCard(it, model::dismissError) }
        OutlinedTextField(
            value = state.draft,
            onValueChange = model::setDraft,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Message") },
            minLines = 1,
            maxLines = 4,
        )
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End) {
            if (pending != null) {
                OutlinedButton(model::stop) { Text("Stop") }
            } else {
                Button(model::send, enabled = state.draft.isNotBlank()) { Text("Send") }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val fromUser = message.role == ChatRole.User
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(if (fromUser) 0.83f else 0.94f),
            colors = CardDefaults.cardColors(
                containerColor = if (fromUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (fromUser) "You" else "Model", style = MaterialTheme.typography.labelMedium)
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, dismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(message)
            TextButton(dismiss) { Text("Dismiss") }
        }
    }
}
