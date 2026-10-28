package com.pianocanvas.midi.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A small polyphonic SoundFont synthesiser. All voice state lives on the audio
 * thread; the UI and the playback scheduler only enqueue commands, so editing
 * and scrolling never block on the synthesiser.
 */
class PianoSynth(
    private val soundFont: SoundFont,
    private val outputSampleRate: Int = 44100,
) : AutoCloseable {

    private sealed interface Command {
        data class NoteOn(val pitch: Int, val velocity: Int, val channel: Int) : Command
        data class NoteOff(val pitch: Int, val channel: Int) : Command
        data class Sustain(val on: Boolean) : Command
        data class Volume(val value: Float) : Command
        data class AllNotesOff(val fast: Boolean) : Command
        data object Shutdown : Command
    }

    private val commands = ConcurrentLinkedQueue<Command>()
    private val voices = ArrayList<Voice>(64)
    private val audioTrack: AudioTrack
    private val renderThread: Thread
    @Volatile private var running = true
    @Volatile private var sustainOn = false
    @Volatile private var masterVolume = 0.62f
    @Volatile private var lastError: String? = null
    private val chunkFrames = 256

    init {
        val minBuffer = AudioTrack.getMinBufferSize(
            outputSampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw IllegalStateException("オーディオバッファを作成できません ($minBuffer)")
        val bufferBytes = maxOf(minBuffer * 2, chunkFrames * 4 * 3)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(outputSampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            audioTrack.release()
            throw IllegalStateException("AudioTrackを初期化できません")
        }
        audioTrack.play()
        renderThread = Thread({ renderLoop() }, "PianoSynth").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun noteOn(pitch: Int, velocity: Int, channel: Int = 0) {
        if (!running) return
        commands.add(Command.NoteOn(pitch.coerceIn(0, 127), velocity.coerceIn(1, 127), channel and 0x0F))
    }

    fun noteOff(pitch: Int, channel: Int = 0) {
        if (!running) return
        commands.add(Command.NoteOff(pitch.coerceIn(0, 127), channel and 0x0F))
    }

    fun setSustain(on: Boolean) {
        if (!running) return
        commands.add(Command.Sustain(on))
    }

    fun setMasterVolume(value: Float) {
        commands.add(Command.Volume(value.coerceIn(0f, 2f)))
    }

    fun allNotesOff(fast: Boolean = false) {
        if (!running) return
        commands.add(Command.AllNotesOff(fast))
    }

    fun errorMessage(): String? = lastError

    override fun close() {
        if (!running) return
        running = false
        commands.add(Command.Shutdown)
        runCatching { audioTrack.pause() }
        runCatching { audioTrack.flush() }
        runCatching { audioTrack.stop() }
        runCatching { renderThread.join(800) }
        runCatching { audioTrack.release() }
    }

    private fun renderLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val floatBuffer = FloatArray(chunkFrames * 2)
        val shortBuffer = ShortArray(chunkFrames * 2)
        try {
            while (running) {
                drainCommands()
                java.util.Arrays.fill(floatBuffer, 0f)
                renderVoices(floatBuffer, chunkFrames)
                // Soft saturation keeps dense chords from hard clipping.
                for (i in floatBuffer.indices) {
                    var x = floatBuffer[i] * masterVolume
                    if (x > -3f && x < 3f) {
                        val x2 = x * x
                        x = x * (27f + x2) / (27f + 9f * x2)
                    } else {
                        x = x.coerceIn(-1f, 1f)
                    }
                    shortBuffer[i] = (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                var written = 0
                val total = shortBuffer.size
                while (written < total) {
                    val n = audioTrack.write(shortBuffer, written, total - written)
                    if (n < 0) {
                        lastError = "AudioTrack write error: $n"
                        running = false
                        break
                    }
                    if (n == 0) break
                    written += n
                }
            }
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            running = false
        } finally {
            voices.clear()
            runCatching { audioTrack.stop() }
        }
    }

    private fun drainCommands() {
        while (true) {
            val cmd = commands.poll() ?: return
            when (cmd) {
                is Command.NoteOn -> startNote(cmd.pitch, cmd.velocity, cmd.channel)
                is Command.NoteOff -> stopNote(cmd.pitch, cmd.channel)
                is Command.Sustain -> {
                    sustainOn = cmd.on
                    if (!cmd.on) {
                        for (v in voices) {
                            if (v.heldBySustain) {
                                v.heldBySustain = false
                                v.release()
                            }
                        }
                    }
                }
                is Command.Volume -> masterVolume = cmd.value
                is Command.AllNotesOff -> {
                    sustainOn = false
                    for (v in voices) {
                        v.heldBySustain = false
                        v.release(fast = cmd.fast)
                    }
                }
                Command.Shutdown -> {
                    running = false
                    return
                }
            }
        }
    }

    private fun startNote(pitch: Int, velocity: Int, channel: Int) {
        // A retriggered key damps the previous voice of the same pitch.
        for (v in voices) {
            if (v.pitch == pitch && v.channel == channel && v.heldBySustain) {
                v.heldBySustain = false
                v.release()
            }
        }
        var started = 0
        for (region in soundFont.regions) {
            if (pitch < region.keyLow || pitch > region.keyHigh) continue
            if (velocity < region.velocityLow || velocity > region.velocityHigh) continue
            if (started >= 4) break
            val voice = Voice(region, pitch, velocity, channel, outputSampleRate, soundFont.sampleData)
            if (voice.done) continue
            voices += voice
            started++
        }
        if (voices.size > 96) {
            // Steal the oldest released voice, otherwise the oldest voice.
            val victim = voices.firstOrNull { it.released } ?: voices.first()
            voices.remove(victim)
        }
    }

    private fun stopNote(pitch: Int, channel: Int) {
        for (v in voices) {
            if (!v.released && v.pitch == pitch && v.channel == channel) {
                if (sustainOn) {
                    v.heldBySustain = true
                } else {
                    v.release()
                }
            }
        }
    }

    private fun renderVoices(buffer: FloatArray, frames: Int) {
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            v.render(buffer, frames)
            if (v.done) it.remove()
        }
    }

    private class Voice(
        private val region: SfRegion,
        val pitch: Int,
        velocity: Int,
        val channel: Int,
        private val outputRate: Int,
        private val sampleData: ShortArray,
    ) {
        private val inverseRate = 1f / outputRate
        var done = false
            private set
        var released = false
            private set
        var heldBySustain = false

        private val rateRatio = region.sampleRate.toDouble() / outputRate
        private val pitchRatio = 2.0.pow(
            ((pitch - region.rootKey) + region.tuneCents / 100.0) / 12.0
        ) * rateRatio
        private var position = region.sampleStart.toDouble()
        private var looping = region.loopMode in 1..3
        private var loopStart = region.loopStart
        private var loopEnd = region.loopEnd
        private var endSample = region.sampleEnd
        private var envLevel = 0f
        private var stage = Stage.ATTACK
        private var stageTime = 0f
        private var releaseStep = 0f
        private val env = region.envelope
        private val velocityGain = (
            0.15 + 0.85 * (velocity / 127.0).pow(1.5)
            ).toFloat()
        private val attenuationGain = 10.0.pow(-region.attenuationCb / 200.0).toFloat()
        private val pan = region.pan.coerceIn(-0.5, 0.5)
        private val leftGain = sqrt((0.5 - pan).toFloat()) * velocityGain * attenuationGain * 0.42f
        private val rightGain = sqrt((0.5 + pan).toFloat()) * velocityGain * attenuationGain * 0.42f

        enum class Stage { DELAY, ATTACK, HOLD, DECAY, SUSTAIN, RELEASE, DONE }

        init {
            if (!looping) {
                loopStart = 0
                loopEnd = 0
            }
            if (looping && (loopEnd <= loopStart + 2 || loopEnd > region.sampleEnd)) {
                looping = false
                loopStart = 0
                loopEnd = 0
            }
            if (env.delay > 0f) stage = Stage.DELAY else stage = Stage.ATTACK
        }

        fun release(fast: Boolean = false) {
            if (released || done) return
            released = true
            stage = Stage.RELEASE
            stageTime = 0f
            val releaseTime = if (fast) 0.035f else env.release.coerceAtLeast(0.01f)
            releaseStep = if (releaseTime <= 0f) 1f else 1f / (releaseTime * outputRate)
            if (region.loopMode == 3) looping = false
        }

        fun render(buffer: FloatArray, frames: Int) {
            val soundFontSamples = sampleData
            var i = 0
            while (i < frames && !done) {
                // ---- envelope ----
                val dt = inverseRate
                when (stage) {
                    Stage.DELAY -> {
                        stageTime += dt
                        if (stageTime >= env.delay) {
                            stage = Stage.ATTACK
                            stageTime = 0f
                        }
                    }
                    Stage.ATTACK -> {
                        stageTime += dt
                        val attack = env.attack.coerceAtLeast(0.001f)
                        envLevel = (stageTime / attack).coerceIn(0f, 1f)
                        if (stageTime >= attack) {
                            envLevel = 1f
                            stage = if (env.hold > 0f) Stage.HOLD else Stage.DECAY
                            stageTime = 0f
                        }
                    }
                    Stage.HOLD -> {
                        stageTime += dt
                        if (stageTime >= env.hold) {
                            stage = Stage.DECAY
                            stageTime = 0f
                        }
                    }
                    Stage.DECAY -> {
                        stageTime += dt
                        val decay = env.decay.coerceAtLeast(0.001f)
                        val t = (stageTime / decay).coerceIn(0f, 1f)
                        envLevel = 1f + (env.sustain - 1f) * t
                        if (t >= 1f) {
                            stage = Stage.SUSTAIN
                            envLevel = env.sustain
                        }
                    }
                    Stage.SUSTAIN -> envLevel = env.sustain
                    Stage.RELEASE -> {
                        envLevel -= releaseStep
                        if (envLevel <= 0.0001f) {
                            envLevel = 0f
                            stage = Stage.DONE
                            done = true
                        }
                    }
                    Stage.DONE -> done = true
                }
                if (done) break

                // ---- sample position ----
                var pos = position
                if (looping && pos >= loopEnd) {
                    val length = (loopEnd - loopStart).toDouble()
                    if (length > 0) {
                        pos = loopStart + (pos - loopEnd) % length
                    } else {
                        looping = false
                    }
                } else if (!looping && pos >= endSample - 1) {
                    done = true
                    break
                }
                val idx = floor(pos).toInt()
                val frac = (pos - idx).toFloat()
                val s0 = if (idx in soundFontSamples.indices) soundFontSamples[idx] / 32768f else 0f
                val s1 = if (idx + 1 in soundFontSamples.indices) soundFontSamples[idx + 1] / 32768f else s0
                val sample = s0 + (s1 - s0) * frac
                position = pos + pitchRatio

                val gain = envLevel
                buffer[i * 2] += sample * leftGain * gain
                buffer[i * 2 + 1] += sample * rightGain * gain
                i++
            }
        }

    }
}
