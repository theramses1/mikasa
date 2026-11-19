package com.mikasa.visuales

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Graba en MP4 (H.264 + AAC) lo que se dibuja en [inputSurface] y el audio PCM que llega por [feedAudio].
 *
 * - El hilo de render dibuja en [inputSurface] (con una superficie EGL) y marca el tiempo de cada frame.
 * - El hilo de audio llama a [feedAudio] con cada bloque del micrófono.
 * - [stop] cierra todo en segundo plano y avisa con [onFinished].
 */
class VideoRecorder(
    private val outFile: File,
    val width: Int,
    val height: Int,
    private val withAudio: Boolean,
    private val onFinished: (File, Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "VideoRecorder"
        private const val TIMEOUT_US = 10_000L
    }

    lateinit var inputSurface: Surface
        private set

    private lateinit var videoCodec: MediaCodec
    private var audioCodec: MediaCodec? = null
    private lateinit var muxer: MediaMuxer

    private val muxLock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var wroteVideo = false
    private class Pending(val track: Int, val data: ByteArray, val info: MediaCodec.BufferInfo)
    private val pending = ArrayList<Pending>()

    /** Momento de inicio (ns, System.nanoTime) que se resta a todas las marcas de tiempo. */
    @Volatile var baseNs = 0L
        private set

    private var drainThread: Thread? = null
    private val audioLock = Object()
    private var audioStopped = false
    private var audioSamples = 0L
    private var audioStartUs = -1L

    /** Prepara los codificadores. Lanza excepción si el dispositivo no soporta el tamaño. */
    fun prepare() {
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 30 * 0.25).toInt().coerceAtLeast(4_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            videoCodec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            videoCodec.release()
            throw e
        }
        inputSurface = videoCodec.createInputSurface()

        if (withAudio) {
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioEngine.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            try {
                audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                    it.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sin audio en el video", e)
                audioCodec?.release()
                audioCodec = null
            }
        }

        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        baseNs = System.nanoTime()
        videoCodec.start()
        audioCodec?.start()
        drainThread = Thread({ drainVideo() }, "video-enc").apply { start() }
    }

    // ---------------- Video ----------------

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = videoCodec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxLock) {
                        videoTrack = muxer.addTrack(videoCodec.outputFormat)
                        maybeStartMuxer()
                    }
                    idx >= 0 -> {
                        val buf = videoCodec.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            write(videoTrack, buf, info, isVideo = true)
                        }
                        videoCodec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error codificando video", e)
        }
    }

    // ---------------- Audio ----------------

    /** Llamado desde el hilo de audio. `startNs` es la marca de tiempo del primer sample. */
    fun feedAudio(pcm: ShortArray, count: Int, startNs: Long) {
        val codec = audioCodec ?: return
        synchronized(audioLock) {
            if (audioStopped || startNs < baseNs) return
            if (audioStartUs < 0) audioStartUs = (startNs - baseNs) / 1000
            try {
                val idx = codec.dequeueInputBuffer(5_000)
                if (idx >= 0) {
                    val inBuf = codec.getInputBuffer(idx)!!
                    inBuf.clear()
                    inBuf.order(ByteOrder.LITTLE_ENDIAN)
                    val n = minOf(count, inBuf.remaining() / 2)
                    for (i in 0 until n) inBuf.putShort(pcm[i])
                    // Marcas de tiempo continuas a partir del número de samples (sin saltos).
                    val pts = audioStartUs + audioSamples * 1_000_000L / AudioEngine.SAMPLE_RATE
                    audioSamples += n
                    codec.queueInputBuffer(idx, 0, n * 2, pts, 0)
                }
                drainAudio(codec, false)
            } catch (e: Exception) {
                Log.e(TAG, "Error codificando audio", e)
                audioStopped = true
            }
        }
    }

    private fun drainAudio(codec: MediaCodec, untilEos: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (untilEos) TIMEOUT_US else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxLock) {
                    audioTrack = muxer.addTrack(codec.outputFormat)
                    maybeStartMuxer()
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        write(audioTrack, buf, info, isVideo = false)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    // ---------------- Muxer ----------------

    private fun maybeStartMuxer() {
        val audioReady = audioCodec == null || audioTrack >= 0
        if (muxerStarted || videoTrack < 0 || !audioReady) return
        muxer.start()
        muxerStarted = true
        for (p in pending) muxer.writeSampleData(p.track, ByteBuffer.wrap(p.data), p.info)
        if (pending.any { it.track == videoTrack }) wroteVideo = true
        pending.clear()
    }

    private fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo, isVideo: Boolean) {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        synchronized(muxLock) {
            if (muxerStarted) {
                muxer.writeSampleData(track, buf, info)
                if (isVideo) wroteVideo = true
            } else if (pending.size < 300) {
                // Llega antes de que el otro codificador esté listo: se guarda una copia.
                val data = ByteArray(info.size).also { buf.get(it) }
                val copy = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                pending.add(Pending(track, data, copy))
            }
        }
    }

    // ---------------- Parar ----------------

    /** Llamar desde el hilo de render después del último frame. No bloquea. */
    fun stop() {
        try { videoCodec.signalEndOfInputStream() } catch (e: Exception) { Log.w(TAG, "EOS video", e) }
        Thread({ finish() }, "video-finish").start()
    }

    private fun finish() {
        drainThread?.join(3000)
        audioCodec?.let { codec ->
            synchronized(audioLock) {
                if (!audioStopped) {
                    audioStopped = true
                    try {
                        val idx = codec.dequeueInputBuffer(TIMEOUT_US * 5)
                        if (idx >= 0) {
                            val pts = audioStartUs.coerceAtLeast(0) + audioSamples * 1_000_000L / AudioEngine.SAMPLE_RATE
                            codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            drainAudio(codec, true)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "EOS audio", e)
                    }
                }
            }
        }
        var ok: Boolean
        synchronized(muxLock) {
            ok = muxerStarted && wroteVideo
            try {
                if (muxerStarted) muxer.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error cerrando el MP4", e)
                ok = false
            }
            try { muxer.release() } catch (_: Exception) {}
        }
        try { videoCodec.stop() } catch (_: Exception) {}
        videoCodec.release()
        audioCodec?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        inputSurface.release()
        onFinished(outFile, ok)
    }
}
