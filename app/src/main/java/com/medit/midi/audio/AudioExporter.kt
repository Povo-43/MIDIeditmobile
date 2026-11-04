package com.medit.midi.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.medit.midi.model.Project
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteOrder

/** Audio container / codec offered by the audio export feature. */
enum class AudioExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
) {
    MP3("MP3", "mp3", "audio/mpeg"),
    AAC("AAC (.m4a)", "m4a", "audio/mp4"),
}

/**
 * Renders the active MIDI track of a project offline through a SoundFont and
 * writes it as MP3 or AAC. The offline path reuses the realtime synth's voice
 * engine, so the result sounds the same as playback. No extra permissions are
 * needed because all audio is synthesised in-process.
 */
object AudioExporter {

    private const val SAMPLE_RATE = 44100
    private const val AUDIO_CHANNELS = 2
    /** Frames per offline render step. Small steps keep MIDI event timing tight. */
    private const val CHUNK_FRAMES = 256
    /** Seconds kept ringing after the last event so release tails are captured. */
    private const val RELEASE_TAIL_SECONDS = 3.5
    /** Guard against absurd output sizes. */
    private const val MAX_DURATION_SECONDS = 20.0 * 60

    private const val ORDER_SUSTAIN = 0
    private const val ORDER_NOTE_OFF = 1
    private const val ORDER_NOTE_ON = 2

    /**
     * Renders every note (plus sustain pedal events) of [Project.track] through
     * [soundFont]. The returned array holds interleaved stereo 16-bit PCM at
     * [SAMPLE_RATE]. [masterGain] is the user volume multiplier so exports
     * sound at the same level as playback.
     */
    fun renderPcm(project: Project, soundFont: SoundFont, masterGain: Float = 1f): ShortArray {
        if (project.track.notes.isEmpty() && !project.track.rawEvents.any { it.isSustain }) {
            return ShortArray(0)
        }

        // Same flattened events the realtime scheduler plays, ordered so that
        // sustain changes come before note-offs, then note-ons on one tick.
        val events = ArrayList<Ev>(project.track.notes.size * 2 + 8)
        for (n in project.track.notes) {
            if (n.durationTicks <= 0) continue
            events += Ev(n.startTick, ORDER_NOTE_ON, n.pitch, n.velocity, n.channel)
            events += Ev(n.endTick, ORDER_NOTE_OFF, n.pitch, 0, n.channel)
        }
        for (e in project.track.rawEvents) {
            if (e.isSustain && e.data.size >= 2) {
                events += Ev(e.tick, ORDER_SUSTAIN, 0, e.data[1], 0)
            }
        }
        if (events.isEmpty()) return ShortArray(0)
        events.sortWith(compareBy({ it.tick }, { it.order }))

        val tempo = project.tempoMap()
        val endTick = maxOf(project.songEndTick(), project.ppq * 4)
        val seconds = (tempo.secondsAt(endTick) + RELEASE_TAIL_SECONDS)
            .coerceAtMost(MAX_DURATION_SECONDS)
        val totalFrames = (seconds * SAMPLE_RATE).toInt().coerceAtLeast(1)

        val synth = PianoSynth.Offline(soundFont, SAMPLE_RATE, masterGain)
        val pcm = ShortArray(totalFrames * AUDIO_CHANNELS)
        try {
            var index = 0
            var frame = 0
            while (frame < totalFrames) {
                val frames = minOf(CHUNK_FRAMES, totalFrames - frame)
                val boundarySeconds = (frame + frames).toDouble() / SAMPLE_RATE
                val boundaryTick = if (frame + frames >= totalFrames) {
                    Double.MAX_VALUE
                } else {
                    tempo.tickAtSeconds(boundarySeconds)
                }
                while (index < events.size && events[index].tick < boundaryTick) {
                    val e = events[index++]
                    when (e.order) {
                        ORDER_SUSTAIN -> synth.setSustain(e.velocity >= 64)
                        ORDER_NOTE_OFF -> synth.noteOff(e.pitch, e.channel)
                        else -> synth.noteOn(e.pitch, e.velocity, e.channel)
                    }
                }
                synth.renderPcm16(pcm, frame * AUDIO_CHANNELS, frames)
                frame += frames
            }
        } finally {
            synth.close()
        }
        return pcm
    }

    /** Encodes stereo 16-bit PCM to an MP3 bitstream (CBR, [bitRateKbps]). */
    fun encodeMp3(pcm: ShortArray, out: OutputStream, bitRateKbps: Int = 192) {
        val lame = createWiredLame()
        val gfp = lame.lame_init()
            ?: throw IOException("MP3エンコーダを初期化できません")
        gfp.in_samplerate = SAMPLE_RATE
        gfp.num_channels = AUDIO_CHANNELS
        gfp.out_samplerate = SAMPLE_RATE
        gfp.brate = bitRateKbps
        gfp.mode = MPEGMode.STEREO
        gfp.bWriteVbrTag = false
        gfp.write_id3tag_automatic = false
        gfp.original = 1
        if (lame.lame_init_params(gfp) != 0) {
            lame.lame_close(gfp)
            throw IOException("MP3エンコーダを設定できません")
        }
        try {
            // jump3r's 16-bit entry point takes int arrays (left / right).
            // It divides by 2^16 internally, so each 16-bit sample must be
            // scaled up (see lowlevel/LameEncoder) or the result is far too
            // quiet.
            val call = 1152 * 8
            val left = IntArray(call)
            val right = IntArray(call)
            val mp3buf = ByteArray(Lame.LAME_MAXMP3BUFFER)
            val totalFrames = pcm.size / AUDIO_CHANNELS
            var frame = 0
            while (frame < totalFrames) {
                val n = minOf(call, totalFrames - frame)
                val base = frame * AUDIO_CHANNELS
                for (i in 0 until n) {
                    left[i] = pcm[base + i * 2].toInt() shl 16
                    right[i] = pcm[base + i * 2 + 1].toInt() shl 16
                }
                val written = lame.lame_encode_buffer_int(gfp, left, right, n, mp3buf, 0, mp3buf.size)
                if (written < 0) throw IOException("MP3エンコードに失敗しました ($written)")
                if (written > 0) out.write(mp3buf, 0, written)
                frame += n
            }
            val flushed = lame.lame_encode_flush(gfp, mp3buf, 0, mp3buf.size)
            if (flushed < 0) throw IOException("MP3エンコードの最終処理に失敗しました ($flushed)")
            if (flushed > 0) out.write(mp3buf, 0, flushed)
            out.flush()
        } finally {
            lame.lame_close(gfp)
        }
    }

    /**
     * Encodes stereo 16-bit PCM to AAC and muxes it into an MP4 (.m4a)
     * container written to [fd]. Uses the platform encoder, so it behaves the
     * same on every device without extra libraries.
     */
    fun encodeAac(pcm: ShortArray, fd: FileDescriptor, bitRate: Int = 192_000) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, AUDIO_CHANNELS)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0x40000)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            var trackIndex = -1
            val info = MediaCodec.BufferInfo()
            val totalFrames = pcm.size / AUDIO_CHANNELS
            var frame = 0
            var sawInputEos = false
            var sawOutputEos = false
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        buffer.clear()
                        buffer.order(ByteOrder.nativeOrder())
                        if (frame >= totalFrames) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0,
                                frame * 1_000_000L / SAMPLE_RATE,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEos = true
                        } else {
                            // Interleaved stereo shorts fit the input buffer.
                            var shorts = buffer.remaining() / 2
                            if (shorts % AUDIO_CHANNELS != 0) shorts -= 1
                            val shortBuffer = buffer.asShortBuffer()
                            shortBuffer.put(pcm, frame * AUDIO_CHANNELS, shorts)
                            val last = frame + shorts / AUDIO_CHANNELS >= totalFrames
                            val bytes = shorts * 2
                            codec.queueInputBuffer(
                                inputIndex, 0, bytes,
                                frame * 1_000_000L / SAMPLE_RATE,
                                if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                            )
                            frame += shorts / AUDIO_CHANNELS
                            if (last) sawInputEos = true
                        }
                    }
                }
                val status = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    status == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    status >= 0 -> {
                        val buffer = codec.getOutputBuffer(status)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (trackIndex >= 0 && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, buffer, info)
                        }
                        codec.releaseOutputBuffer(status, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                    }
                }
            }
            muxer.stop()
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer.release() }
        }
    }

    /**
     * Builds a jump3r [Lame] instance with every internal module wired, the
     * same way the reference Main entry point does it.
     */
    private fun createWiredLame(): Lame {
        val lame = Lame()
        val ga = GainAnalysis()
        val bs = BitStream()
        val p = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()
        val parse = Parse()
        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        p.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        parse.setModules(ver, id3, p)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)
        return lame
    }
}

/**
 * Static scheduling event; same shape as the realtime playback scheduler.
 */
private class Ev(
    val tick: Int,
    val order: Int,
    val pitch: Int,
    val velocity: Int,
    val channel: Int,
)
