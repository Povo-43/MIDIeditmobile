package com.medit.midi.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface AudioStatus {
    data object Loading : AudioStatus
    data class Ready(val fontName: String, val presetName: String, val regionCount: Int) : AudioStatus
    data class Failed(val message: String) : AudioStatus
}

/**
 * Owns the SoundFont and the realtime synth. Loading happens off the main
 * thread and the UI observes [status].
 */
class AudioEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<AudioStatus>(AudioStatus.Loading)
    val status: StateFlow<AudioStatus> = _status.asStateFlow()

    @Volatile private var synth: PianoSynth? = null
    @Volatile private var font: SoundFont? = null
    /** User volume multiplier (1 = neutral), re-applied whenever a synth is installed. */
    @Volatile private var userVolume: Float = 1f
    private var loadToken = 0

    fun loadBundled() {
        val token = ++loadToken
        _status.value = AudioStatus.Loading
        scope.launch {
            try {
                val bytes = context.assets.open("piano.sf2").use { it.readBytes() }
                install(SoundFontLoader.load(bytes), token)
            } catch (t: Throwable) {
                if (token == loadToken) {
                    _status.value = AudioStatus.Failed("音源を読み込めません: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    fun loadExternal(file: File) {
        val token = ++loadToken
        _status.value = AudioStatus.Loading
        scope.launch {
            try {
                // Stream from disk instead of holding the whole bank in heap.
                install(SoundFontLoader.load(file), token)
            } catch (t: Throwable) {
                if (token == loadToken) {
                    _status.value = AudioStatus.Failed("SoundFontを読み込めません: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun install(font: SoundFont, token: Int) {
        if (token != loadToken) return
        val newSynth = try {
            PianoSynth(font)
        } catch (t: Throwable) {
            if (token == loadToken) {
                _status.value = AudioStatus.Failed("オーディオを初期化できません: ${t.message ?: t.javaClass.simpleName}")
            }
            return
        }
        // Keep the settings volume across soundfont switches and restarts.
        newSynth.setMasterVolume(userVolume)
        val old = synth
        synth = newSynth
        this.font = font
        _status.value = AudioStatus.Ready(font.name, font.presetName, font.regionCount)
        old?.allNotesOff()
        old?.close()
    }

    /** The SoundFont currently used by the realtime synth, if loaded. */
    fun activeSoundFont(): SoundFont? = font

    fun noteOn(pitch: Int, velocity: Int, channel: Int = 0) {
        synth?.noteOn(pitch, velocity, channel)
    }

    fun noteOff(pitch: Int, channel: Int = 0) {
        synth?.noteOff(pitch, channel)
    }

    fun setSustain(on: Boolean) {
        synth?.setSustain(on)
    }

    fun allNotesOff(fast: Boolean = false) {
        synth?.allNotesOff(fast)
    }

    fun setVolume(value: Float) {
        userVolume = value.coerceIn(0f, 4f)
        synth?.setMasterVolume(userVolume)
    }

    /** The user volume multiplier currently applied to the synth (1 = neutral). */
    fun currentUserVolume(): Float = userVolume

    fun isReady(): Boolean = synth != null && _status.value is AudioStatus.Ready

    fun errorMessage(): String? = synth?.errorMessage()

    fun shutdown() {
        loadToken++
        synth?.close()
        synth = null
        font = null
        _status.value = AudioStatus.Loading
    }
}
