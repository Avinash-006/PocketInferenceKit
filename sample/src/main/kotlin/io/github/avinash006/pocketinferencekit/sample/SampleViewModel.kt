package io.github.avinash006.pocketinferencekit.sample

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.avinash006.pocketinferencekit.core.Backend
import io.github.avinash006.pocketinferencekit.core.ChatMessage
import io.github.avinash006.pocketinferencekit.core.ChatRole
import io.github.avinash006.pocketinferencekit.core.ChatSession
import io.github.avinash006.pocketinferencekit.core.GenerationEvent
import io.github.avinash006.pocketinferencekit.core.GenerationStats
import io.github.avinash006.pocketinferencekit.core.LocalLMEngine
import io.github.avinash006.pocketinferencekit.core.LocalLMException
import io.github.avinash006.pocketinferencekit.core.PocketInferenceKit
import io.github.avinash006.pocketinferencekit.models.DownloadManager
import io.github.avinash006.pocketinferencekit.models.DownloadState
import io.github.avinash006.pocketinferencekit.models.ModelCache
import io.github.avinash006.pocketinferencekit.models.ModelCatalog
import io.github.avinash006.pocketinferencekit.models.ModelSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class SampleState(
    val selected: ModelSpec = ModelCatalog.Qwen3_0_6B,
    val download: DownloadState = DownloadState.Idle,
    val installed: Set<String> = emptySet(),
    val wifiOnly: Boolean = false,
    val chatOpen: Boolean = false,
    val loading: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val pendingPrompt: String? = null,
    val streaming: String = "",
    val draft: String = "",
    val backend: Backend? = null,
    val stats: GenerationStats? = null,
    val error: String? = null,
)

internal class SampleViewModel(
    private val application: Application,
    private val saved: SavedStateHandle,
) : AndroidViewModel(application) {
    private val downloads = DownloadManager(application)
    private val cache = ModelCache(application)
    private val preferences = application.getSharedPreferences("sample_chat", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(
        SampleState(
            selected = ModelCatalog.entries.find { it.id == saved.get<String>(SELECTED) } ?: ModelCatalog.Qwen3_0_6B,
            wifiOnly = saved[WIFI] ?: false,
            draft = saved[DRAFT] ?: "",
        ),
    )
    val state = mutableState.asStateFlow()

    private var monitor: Job? = null
    private var loading: Job? = null
    private var generation: Job? = null
    private var session: ChatSession? = null
    private var engine: LocalLMEngine? = null
    private var closingEngine: LocalLMEngine? = null

    init {
        observeSelected()
        refreshCache()
        if (saved.get<Boolean>(CHAT) == true) openChat()
    }

    fun select(model: ModelSpec) {
        if (state.value.loading || model.id == state.value.selected.id) return
        saved[SELECTED] = model.id
        mutableState.update { it.copy(selected = model, download = DownloadState.Idle, error = null) }
        observeSelected()
    }

    fun setWifiOnly(enabled: Boolean) {
        saved[WIFI] = enabled
        mutableState.update { it.copy(wifiOnly = enabled) }
    }

    fun download() = modelAction { model -> downloads.start(model, state.value.wifiOnly) }

    fun pause() = modelAction { model -> downloads.pause(model) }

    fun resume() = modelAction(downloads::resume)

    fun cancel() = modelAction(downloads::cancel)

    fun delete() = modelAction { model ->
        if (!cache.delete(model)) {
            mutableState.update { it.copy(error = "The file is in use or is no longer installed.") }
        }
        refreshCache()
        observeSelected()
    }

    fun openChat() {
        if (loading?.isActive == true || engine != null) return
        val snapshot = state.value
        val model = snapshot.selected
        mutableState.update { it.copy(loading = true, error = null) }
        loading = viewModelScope.launch {
            var opened: LocalLMEngine? = null
            try {
                closingEngine?.awaitClosed()
                closingEngine = null
                val history = withContext(Dispatchers.IO) { loadHistory(model) }
                if (model.sha256 !in snapshot.installed && snapshot.download !is DownloadState.Paused) {
                    downloads.start(model, snapshot.wifiOnly)
                }
                val active = PocketInferenceKit.load(application, model)
                opened = active
                currentCoroutineContext().ensureActive()
                val chat = active.newChat("You are a helpful assistant. Keep answers concise.", history = history)
                engine = active
                session = chat
                opened = null
                saved[CHAT] = true
                mutableState.update {
                    it.copy(
                        chatOpen = true,
                        messages = history,
                        loading = false,
                        backend = active.activeBackend,
                        stats = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                saved[CHAT] = false
                showError(error)
            } finally {
                opened?.close()
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun backToModels() {
        stop()
        loading?.cancel()
        releaseChat()
        saved[CHAT] = false
        mutableState.update { it.copy(chatOpen = false, backend = null, error = null, stats = null) }
    }

    fun setDraft(value: String) {
        if (value.length > MAX_MESSAGE_CHARS) return
        saved[DRAFT] = value
        mutableState.update { it.copy(draft = value) }
    }

    fun send() {
        val chat = session ?: return
        val snapshot = state.value
        val prompt = snapshot.draft.trim()
        if (prompt.isBlank() || generation?.isActive == true) return
        setDraft("")
        mutableState.update { it.copy(pendingPrompt = prompt, streaming = "", error = null) }
        generation = viewModelScope.launch {
            var finished = false
            val text = StringBuilder()
            try {
                chat.send(prompt).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            text.append(event.text)
                            mutableState.update { it.copy(streaming = text.toString()) }
                        }
                        is GenerationEvent.Finished -> {
                            val messages = (snapshot.messages + ChatMessage(ChatRole.User, prompt) +
                                ChatMessage(ChatRole.Assistant, text.toString())).takeLast(MAX_HISTORY)
                            finished = true
                            mutableState.update { it.copy(messages = messages, stats = event.stats, pendingPrompt = null, streaming = "") }
                            withContext(Dispatchers.IO) { saveHistory(snapshot.selected, messages) }
                        }
                        is GenerationEvent.Error -> throw event.exception
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(error)
            } finally {
                if (!finished && state.value.draft.isEmpty()) setDraft(prompt)
                mutableState.update { it.copy(pendingPrompt = null, streaming = "") }
            }
        }
    }

    fun stop() {
        generation?.cancel()
    }

    fun dismissError() {
        mutableState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        releaseChat()
    }

    private fun releaseChat() {
        session?.close()
        session = null
        engine?.let {
            closingEngine = it
            it.close()
        }
        engine = null
    }

    private fun observeSelected() {
        val model = state.value.selected
        monitor?.cancel()
        monitor = viewModelScope.launch {
            downloads.progress(model).collect { download ->
                mutableState.update { it.copy(download = download) }
                if (download is DownloadState.Completed) refreshCache()
            }
        }
    }

    private fun refreshCache() {
        viewModelScope.launch {
            try {
                val installed = cache.list().map { it.sha256 }.toSet()
                mutableState.update { it.copy(installed = installed) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LocalLMException) {
                showError(error)
            }
        }
    }

    private fun modelAction(action: suspend (ModelSpec) -> Unit) {
        val model = state.value.selected
        viewModelScope.launch {
            try {
                action(model)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    private fun showError(error: Throwable) {
        mutableState.update { it.copy(error = error.message ?: "The operation failed. Try again.") }
    }

    private fun loadHistory(model: ModelSpec): List<ChatMessage> {
        val encoded = preferences.getString("history_${model.id}", null) ?: return emptyList()
        val messages = JSONArray(encoded)
        return List(messages.length()) { index ->
            val item = messages.getJSONObject(index)
            ChatMessage(ChatRole.valueOf(item.getString("role")), item.getString("text"))
        }.takeLast(MAX_HISTORY)
    }

    // Check the synchronous commit result. The edit KTX helper returns Unit.
    @SuppressLint("UseKtx")
    private fun saveHistory(model: ModelSpec, messages: List<ChatMessage>) {
        val encoded = JSONArray()
        for (message in messages) {
            encoded.put(JSONObject().put("role", message.role.name).put("text", message.text.take(MAX_MESSAGE_CHARS)))
        }
        if (!preferences.edit().putString("history_${model.id}", encoded.toString()).commit()) {
            error("Could not save the chat on this device.")
        }
    }

    private companion object {
        const val SELECTED = "selected"
        const val WIFI = "wifi"
        const val CHAT = "chat"
        const val DRAFT = "draft"
        const val MAX_HISTORY = 32
        const val MAX_MESSAGE_CHARS = 16_000
    }
}
