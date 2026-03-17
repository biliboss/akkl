package com.cdp.agent

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class AudioCapturer {

    companion object {
        private const val TAG = "AudioCapturer"
        private const val SAMPLE_RATE = 16000
        private const val BIT_RATE = 32000
    }

    private val isRunning = AtomicBoolean(false)
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var useOpus = true

    fun start() {
        if (isRunning.getAndSet(true)) return
        audioQueue.clear()

        recordThread = Thread {
            try {
                startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Recording failed", e)
                isRunning.set(false)
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        recordThread?.interrupt()
        recordThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        audioQueue.clear()
    }

    fun drainEncodedChunks(): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        while (true) {
            val chunk = audioQueue.poll() ?: break
            chunks.add(chunk)
        }
        return chunks
    }

    private fun startRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            isRunning.set(false)
            return
        }

        // Try Opus encoder via MediaCodec
        var codec: MediaCodec? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            useOpus = true
            Log.d(TAG, "Using Opus encoder")
        } catch (e: Exception) {
            Log.w(TAG, "Opus encoder unavailable, falling back to PCM", e)
            codec?.release()
            codec = null
            useOpus = false
        }

        audioRecord?.startRecording()
        Log.d(TAG, "Audio recording started")

        val buffer = ShortArray(bufferSize / 2)

        if (codec != null) {
            recordWithCodec(codec, buffer)
        } else {
            recordPcm(buffer)
        }
    }

    private fun recordWithCodec(codec: MediaCodec, buffer: ShortArray) {
        try {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRunning.get() && !Thread.interrupted()) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read <= 0) continue

                // Feed PCM to encoder
                val inputIdx = codec.dequeueInputBuffer(10000)
                if (inputIdx >= 0) {
                    val inputBuf = codec.getInputBuffer(inputIdx) ?: continue
                    inputBuf.clear()
                    // Convert short[] to byte[]
                    val bytes = ByteArray(read * 2)
                    for (i in 0 until read) {
                        bytes[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        bytes[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                    }
                    inputBuf.put(bytes)
                    codec.queueInputBuffer(inputIdx, 0, bytes.size, System.nanoTime() / 1000, 0)
                }

                // Drain encoded output
                while (true) {
                    val outputIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
                    if (outputIdx < 0) break
                    val outputBuf = codec.getOutputBuffer(outputIdx) ?: continue
                    if (bufferInfo.size > 0) {
                        val encoded = ByteArray(bufferInfo.size)
                        outputBuf.position(bufferInfo.offset)
                        outputBuf.get(encoded)
                        audioQueue.offer(encoded)
                        // Keep queue bounded
                        while (audioQueue.size > 50) audioQueue.poll()
                    }
                    codec.releaseOutputBuffer(outputIdx, false)
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
    }

    private fun recordPcm(buffer: ShortArray) {
        // Fallback: send raw PCM chunks (16-bit LE mono 16kHz)
        while (isRunning.get() && !Thread.interrupted()) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (read <= 0) continue

            val bytes = ByteArray(read * 2)
            for (i in 0 until read) {
                bytes[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                bytes[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
            }
            audioQueue.offer(bytes)
            while (audioQueue.size > 50) audioQueue.poll()
        }
    }

    fun isUsingOpus(): Boolean = useOpus
}
