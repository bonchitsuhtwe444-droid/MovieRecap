package com.example.movierecap

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioExtractor {

    private const val TARGET_RATE = 16000

    fun extractToWav(
        context: Context,
        videoUri: Uri,
        outFile: File,
        onProgress: (Int) -> Unit
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, videoUri, null)

        var audioTrack = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioTrack = i
                format = f
                break
            }
        }
        require(audioTrack >= 0 && format != null) { "Video ထဲမှာ audio track မတွေ့ပါ" }

        extractor.selectTrack(audioTrack)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
            format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val raf = RandomAccessFile(outFile, "rw")
        raf.setLength(0)
        raf.write(ByteArray(44))

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var totalPcmBytes = 0L
        var resamplePos = 0.0

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        if (durationUs > 0) {
                            onProgress(((extractor.sampleTime * 100) / durationUs).toInt().coerceIn(0, 99))
                        }
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val nf = codec.outputFormat
                    srcRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    srcChannels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                outIdx >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = shorts.remaining() / srcChannels

                        val mono = DoubleArray(frames)
                        for (i in 0 until frames) {
                            var sum = 0.0
                            for (c in 0 until srcChannels) sum += shorts.get().toDouble()
                            mono[i] = sum / srcChannels
                        }

                        val step = srcRate.toDouble() / TARGET_RATE
                        val out = ByteBuffer.allocate((frames / step).toInt() * 2 + 16)
                            .order(ByteOrder.LITTLE_ENDIAN)

                        while (true) {
                            val pos = resamplePos
                            val i0 = pos.toInt().coerceAtLeast(0)
                            if (i0 + 1 >= frames) break
                            val frac = (pos - i0).coerceAtLeast(0.0)
                            val s = mono[i0] * (1 - frac) + mono[i0 + 1] * frac
                            out.putShort(s.toInt().coerceIn(-32768, 32767).toShort())
                            resamplePos += step
                        }
                        resamplePos -= frames

                        raf.write(out.array(), 0, out.position())
                        totalPcmBytes += out.position()
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }

        writeWavHeader(raf, totalPcmBytes)
        raf.close()
        codec.stop()
        codec.release()
        extractor.release()
        onProgress(100)
    }

    private fun writeWavHeader(raf: RandomAccessFile, pcmBytes: Long) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt((36 + pcmBytes).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(TARGET_RATE)
        header.putInt(TARGET_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(pcmBytes.toInt())
        raf.seek(0)
        raf.write(header.array())
    }
}
