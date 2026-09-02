package dev.local.physicalmemory.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.local.physicalmemory.domain.MemoryResult
import dev.local.physicalmemory.domain.PhysicalMemory
import dev.local.physicalmemory.domain.model.ItemRecord
import dev.local.physicalmemory.domain.parser.Command
import dev.local.physicalmemory.domain.parser.CommandParser
import dev.local.physicalmemory.voice.*
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val input: String = "",
    val isSubmitting: Boolean = false,
    val isLoadingRecords: Boolean = true,
    val result: MemoryResult? = null,
    val recentItems: List<ItemRecord> = emptyList(),
    val recordsError: String? = null,
    val selectedEngine: SpeechEngine = SpeechEngine.SYSTEM,
    val speechState: SpeechRecognitionState = SpeechRecognitionState.Idle,
    val speechMetrics: AsrMetrics? = null,
    val speechAvailability: Map<SpeechEngine, SpeechAvailability> = emptyMap(),
    val transcription: String = "",
    val permissionMessage: String? = null,
    val capabilityDetails: String = "",
    val debugAudioEnabled: Boolean = false,
) {
    val canSubmit: Boolean get() = input.isNotBlank() && !isSubmitting
}

class HomeViewModel(
    private val memory: PhysicalMemory,
    private val parser: CommandParser = CommandParser(),
    private val speechInputs: Map<SpeechEngine, SpeechInput> = emptyMap(),
    capabilityDetails: String = "",
    private val commandObserver: (String?, String, Command, String) -> Unit = { _, _, _, _ -> },
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState())
    val state = mutableState.asStateFlow()
    private var recordsJob: kotlinx.coroutines.Job? = null
    private val speechJobs = mutableListOf<Job>()
    private var activeSpeechSession: String? = null
    private var consumedSession: String? = null

    init {
        mutableState.update { it.copy(capabilityDetails = capabilityDetails,
            selectedEngine = if (SpeechEngine.QWEN3_ASR in speechInputs) SpeechEngine.QWEN3_ASR else SpeechEngine.SYSTEM) }
        reloadRecords()
        speechInputs.forEach { (engine, input) ->
            viewModelScope.launch { input.availability.collect { available ->
                mutableState.update { it.copy(speechAvailability = it.speechAvailability + (engine to available)) }
            } }
        }
        observeSpeech()
    }

    private fun observeSpeech() {
        speechJobs.forEach { it.cancel() }; speechJobs.clear()
        val input = speechInputs[state.value.selectedEngine] ?: return
        speechJobs += viewModelScope.launch {
            input.state.collect { speech ->
                if (speech.sessionId != null && speech.sessionId != activeSpeechSession) return@collect
                mutableState.update {
                    it.copy(speechState = speech, transcription = when (speech) {
                        is SpeechRecognitionState.Partial -> speech.text
                        is SpeechRecognitionState.Final -> speech.text
                        is SpeechRecognitionState.Initializing -> ""
                        else -> it.transcription
                    })
                }
                if (speech is SpeechRecognitionState.Final && consumedSession != speech.sessionId) {
                    consumedSession = speech.sessionId
                    activeSpeechSession = null
                    // Claim this session BEFORE parsing/scheduling. A repeated Final can never submit again.
                    mutableState.update { it.copy(input = speech.text) }
                    submitCommand(speech.text, speech.sessionId)
                } else if (speech is SpeechRecognitionState.Error) activeSpeechSession = null
            }
        }
        speechJobs += viewModelScope.launch { input.metrics.collect { metrics ->
            mutableState.update { it.copy(speechMetrics = metrics) }
        } }
    }

    fun selectEngine(engine: SpeechEngine) {
        if (engine == state.value.selectedEngine) return
        cancelSpeech()
        mutableState.update { it.copy(selectedEngine = engine, speechState = SpeechRecognitionState.Idle,
            speechMetrics = null, transcription = "", permissionMessage = null) }
        observeSpeech()
    }

    fun startSpeech() {
        if (state.value.isSubmitting || activeSpeechSession != null) return
        val input = speechInputs[state.value.selectedEngine] ?: return
        val id = "ASR-" + UUID.randomUUID().toString()
        activeSpeechSession = id
        mutableState.update { it.copy(permissionMessage = null, transcription = "", speechState = SpeechRecognitionState.Initializing(id)) }
        input.startListening(id)
    }

    fun stopSpeech() { speechInputs[state.value.selectedEngine]?.stopListening() }
    fun setDebugAudioEnabled(enabled: Boolean) {
        speechInputs[SpeechEngine.QWEN3_ASR]?.setDebugAudioEnabled(enabled)
        mutableState.update { it.copy(debugAudioEnabled = enabled) }
    }
    fun cancelSpeech() {
        activeSpeechSession = null
        speechInputs[state.value.selectedEngine]?.cancel()
        mutableState.update { it.copy(speechState = SpeechRecognitionState.Idle) }
    }
    fun onPageStopped() = cancelSpeech()
    fun onPermissionDenied() {
        cancelSpeech()
        mutableState.update { it.copy(permissionMessage = "麦克风权限未开启。仍可输入文字；需要语音时可再次点击或到应用设置授权。") }
    }

    fun onInputChanged(value: String) {
        if (!state.value.isSubmitting && activeSpeechSession != null) cancelSpeech()
        if (!state.value.isSubmitting) mutableState.update {
            it.copy(input = value.take(512),
                result = if (it.result?.suggestions?.isNotEmpty() == true) null else it.result)
        }
    }

    fun reloadRecords() {
        recordsJob?.cancel()
        mutableState.update { it.copy(isLoadingRecords = true, recordsError = null) }
        recordsJob = viewModelScope.launch {
            memory.recentItems().catch { error ->
                if (error is CancellationException) throw error
                mutableState.update { it.copy(isLoadingRecords = false, recordsError = "暂时无法读取记录，请重试") }
            }.collect { records ->
                mutableState.update { it.copy(recentItems = records, isLoadingRecords = false, recordsError = null) }
            }
        }
    }

    fun submit() {
        val snapshot = state.value
        if (!snapshot.canSubmit) return
        cancelSpeech()
        submitCommand(snapshot.input)
    }

    private fun submitCommand(text: String, speechSessionId: String? = null) {
        if (text.isBlank() || state.value.isSubmitting) return
        val command = parser.parse(text)
        // Set before launching to ignore double taps and concurrent IME submissions.
        mutableState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            try {
                val result = memory.execute(command)
                mutableState.update {
                    it.copy(result = result, input = if (command is Command.Store) "" else it.input)
                }
                runCatching { commandObserver(speechSessionId, text, command, result.title) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(result = MemoryResult("操作未完成", "暂时无法完成操作，请重试。输入内容已保留。"))
                }
                runCatching { commandObserver(speechSessionId, text, command, "操作未完成") }
            } finally {
                mutableState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    fun selectCandidate(id: Long) {
        val snapshot = state.value
        if (snapshot.isSubmitting || snapshot.result?.suggestions?.none { it.id == id } != false) return
        cancelSpeech()
        mutableState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            try {
                val result = memory.selectItem(id)
                mutableState.update { it.copy(result = result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(result = snapshot.result?.copy(text = "暂时无法读取位置，请再次选择。"))
                }
            } finally {
                mutableState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    companion object {
        fun factory(memory: PhysicalMemory, speechInputs: Map<SpeechEngine, SpeechInput> = emptyMap(),
            capabilityDetails: String = "") = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(HomeViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(memory, speechInputs = speechInputs, capabilityDetails = capabilityDetails) as T
            }
        }
    }

    override fun onCleared() {
        activeSpeechSession = null
        speechInputs.values.forEach { it.release() }
        super.onCleared()
    }
}
