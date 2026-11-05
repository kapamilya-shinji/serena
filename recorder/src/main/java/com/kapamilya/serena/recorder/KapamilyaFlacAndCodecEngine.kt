package com.kapamilya.serena.recorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Kapamilya Serena Recorder — Universal Audio Codec & Lossless FLAC Engine
 * Supports:
 * - Lossless FLAC (.flac) encoding (RFC 9639 compliant with STREAMINFO, MD5, Vorbis Comments, CRC-8 & CRC-16)
 * - Lossless PCM WAV (.wav) encoding & decoding (48kHz 16-bit)
 * - AAC / M4A (.m4a) hardware encoding via Android MediaCodec + MediaMuxer
 * - Universal decoding of .flac, .wav, .mp3, .m4a, .aac, .ogg, .opus via Android MediaExtractor + MediaCodec
 */
object KapamilyaFlacAndCodecEngine {

    const val SAMPLE_RATE = 48000

    /**
     * Write 48kHz 16-bit Mono PCM samples as a standard RIFF WAV file.
     */
    fun writeWav(samples: FloatArray, sampleRate: Int = SAMPLE_RATE, out: OutputStream) {
        val pcmBytes = floatToPcm16LeBytes(samples)
        val totalDataLen = pcmBytes.size
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + totalDataLen)
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // PCM format
        header.putShort(1) // 1 channel (Mono)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // byte rate
        header.putShort(2) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        out.write(header.array())
        out.write(pcmBytes)
        out.flush()
    }

    /**
     * Write 48kHz 16-bit Mono PCM samples as a genuine lossless RFC 9639 FLAC (.flac) file
     * with Vorbis Comment metadata (ARTIST=Shinji Sakiyama, TITLE=...) and full CRC-8 / CRC-16 frame integrity.
     */
    fun writeFlac(
        samples: FloatArray,
        sampleRate: Int = SAMPLE_RATE,
        title: String = "Untitled Track",
        artist: String = "Shinji Sakiyama",
        out: OutputStream
    ) {
        val pcmShorts = ShortArray(samples.size)
        val md5 = MessageDigest.getInstance("MD5")
        val leBuf = ByteArray(2)
        for (i in samples.indices) {
            val s = (samples[i].coerceIn(-1.0f, 1.0f) * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
            pcmShorts[i] = s
            leBuf[0] = (s.toInt() and 0xFF).toByte()
            leBuf[1] = ((s.toInt() shr 8) and 0xFF).toByte()
            md5.update(leBuf, 0, 2)
        }
        val md5Bytes = md5.digest()

        // 1. Magic "fLaC"
        out.write("fLaC".toByteArray(Charsets.US_ASCII))

        // 2. STREAMINFO metadata block (type = 0, not last = 0x00, length = 34)
        val blockSize = 4096
        val streamInfo = ByteArray(38)
        streamInfo[0] = 0x00 // not last metadata block, type 0 (STREAMINFO)
        streamInfo[1] = 0x00
        streamInfo[2] = 0x00
        streamInfo[3] = 34
        // min block size = 4096, max block size = 4096
        streamInfo[4] = ((blockSize shr 8) and 0xFF).toByte()
        streamInfo[5] = (blockSize and 0xFF).toByte()
        streamInfo[6] = ((blockSize shr 8) and 0xFF).toByte()
        streamInfo[7] = (blockSize and 0xFF).toByte()
        // min/max frame size = 0 (unknown) -> bytes 8..13 are 0
        // 20 bits sampleRate (48000), 3 bits channels-1 (0), 5 bits bps-1 (15), 36 bits total samples
        val totalSamples = pcmShorts.size.toLong()
        streamInfo[14] = ((sampleRate shr 12) and 0xFF).toByte()
        streamInfo[15] = ((sampleRate shr 4) and 0xFF).toByte()
        val bpsMinus1 = 15 // 16-bit
        streamInfo[16] = (((sampleRate and 0x0F) shl 4) or 0x00 or ((bpsMinus1 shr 4) and 0x01)).toByte()
        streamInfo[17] = (((bpsMinus1 and 0x0F) shl 4) or ((totalSamples shr 32).toInt() and 0x0F)).toByte()
        streamInfo[18] = ((totalSamples shr 24) and 0xFF).toByte()
        streamInfo[19] = ((totalSamples shr 16) and 0xFF).toByte()
        streamInfo[20] = ((totalSamples shr 8) and 0xFF).toByte()
        streamInfo[21] = (totalSamples and 0xFF).toByte()
        System.arraycopy(md5Bytes, 0, streamInfo, 22, 16)
        out.write(streamInfo)

        // 3. VORBIS_COMMENT metadata block (type = 4, last = 0x84)
        val vorbisPayload = buildVorbisCommentPayload(title, artist)
        val vorbisHeader = ByteArray(4)
        vorbisHeader[0] = (0x80 or 4).toByte() // last metadata block, type 4
        vorbisHeader[1] = ((vorbisPayload.size shr 16) and 0xFF).toByte()
        vorbisHeader[2] = ((vorbisPayload.size shr 8) and 0xFF).toByte()
        vorbisHeader[3] = (vorbisPayload.size and 0xFF).toByte()
        out.write(vorbisHeader)
        out.write(vorbisPayload)

        // 4. Write Audio Frames (Verbatim Lossless Subframes with CRC-8 & CRC-16)
        var offset = 0
        var frameNumber = 0L
        while (offset < pcmShorts.size) {
            val count = minOf(blockSize, pcmShorts.size - offset)
            val frameBytes = encodeFlacVerbatimFrame(pcmShorts, offset, count, frameNumber, sampleRate)
            out.write(frameBytes)
            offset += count
            frameNumber++
        }
        out.flush()
    }

    private fun buildVorbisCommentPayload(title: String, artist: String): ByteArray {
        val baos = ByteArrayOutputStream()
        fun writeLeInt(v: Int) {
            baos.write(v and 0xFF)
            baos.write((v shr 8) and 0xFF)
            baos.write((v shr 16) and 0xFF)
            baos.write((v shr 24) and 0xFF)
        }
        val vendor = "Kapamilya Serena Recorder v5.3.0".toByteArray(Charsets.UTF_8)
        writeLeInt(vendor.size)
        baos.write(vendor)
        val comments = listOf(
            "TITLE=$title".toByteArray(Charsets.UTF_8),
            "ARTIST=$artist".toByteArray(Charsets.UTF_8),
            "ENCODER=Kapamilya Serena Recorder v5.3.0 (Bayanihan • Magna Carta • Kapamilya)".toByteArray(Charsets.UTF_8)
        )
        writeLeInt(comments.size)
        for (c in comments) {
            writeLeInt(c.size)
            baos.write(c)
        }
        return baos.toByteArray()
    }

    private fun encodeFlacVerbatimFrame(
        pcm: ShortArray,
        offset: Int,
        count: Int,
        frameNumber: Long,
        sampleRate: Int
    ): ByteArray {
        val headerOut = ByteArrayOutputStream(32)
        // Sync code 0xFFF8 (fixed block size strategy = 0)
        headerOut.write(0xFF)
        headerOut.write(0xF8)
        // Block size: 0111 = 16-bit (blockSize - 1) at end of header; Sample rate: 1010 = 48kHz (or 1100 = 16-bit kHz)
        val srCode = if (sampleRate == 48000) 0x0A else if (sampleRate == 44100) 0x09 else 0x0A
        headerOut.write((0x70 or srCode))
        // Channel assignment: 0000 (1 channel mono), Sample size: 100 (16 bits), Reserved: 0 -> 0x08
        headerOut.write(0x08)
        // UTF-8 coded frame number
        writeUtf8Long(headerOut, frameNumber)
        // 16-bit (blockSize - 1) big-endian because block size bits = 0111
        val bsMinus1 = count - 1
        headerOut.write((bsMinus1 shr 8) and 0xFF)
        headerOut.write(bsMinus1 and 0xFF)
        val headerBytes = headerOut.toByteArray()
        val crc8 = computeCrc8(headerBytes, headerBytes.size)

        val frameOut = ByteArrayOutputStream(headerBytes.size + 2 + count * 2 + 2)
        frameOut.write(headerBytes)
        frameOut.write(crc8)

        // Subframe header: 0 (pad) + 000001 (VERBATIM) + 0 (no wasted bits) = 0x02
        frameOut.write(0x02)
        // Uncompressed 16-bit big-endian samples
        for (i in 0 until count) {
            val s = pcm[offset + i].toInt()
            frameOut.write((s shr 8) and 0xFF)
            frameOut.write(s and 0xFF)
        }

        val bodyBytes = frameOut.toByteArray()
        val crc16 = computeCrc16(bodyBytes, bodyBytes.size)
        frameOut.write((crc16 shr 8) and 0xFF)
        frameOut.write(crc16 and 0xFF)
        return frameOut.toByteArray()
    }

    private fun writeUtf8Long(out: ByteArrayOutputStream, v: Long) {
        if (v < 0x80) {
            out.write(v.toInt())
        } else if (v < 0x800) {
            out.write((0xC0 or ((v shr 6).toInt() and 0x1F)))
            out.write((0x80 or (v.toInt() and 0x3F)))
        } else if (v < 0x10000) {
            out.write((0xE0 or ((v shr 12).toInt() and 0x0F)))
            out.write((0x80 or ((v shr 6).toInt() and 0x3F)))
            out.write((0x80 or (v.toInt() and 0x3F)))
        } else {
            out.write((0xF0 or ((v shr 18).toInt() and 0x07)))
            out.write((0x80 or ((v shr 12).toInt() and 0x3F)))
            out.write((0x80 or ((v shr 6).toInt() and 0x3F)))
            out.write((0x80 or (v.toInt() and 0x3F)))
        }
    }

    private fun computeCrc8(data: ByteArray, length: Int): Int {
        var crc = 0
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (b in 0 until 8) {
                crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    private fun computeCrc16(data: ByteArray, length: Int): Int {
        var crc = 0
        for (i in 0 until length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            for (b in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    /**
     * Encode 48kHz Mono float samples to M4A (AAC-LC 256kbps) using Android MediaCodec + MediaMuxer.
     */
    fun writeM4aFile(samples: FloatArray, sampleRate: Int = SAMPLE_RATE, outputFile: File) {
        val pcmBytes = floatToPcm16LeBytes(samples)
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, sampleRate, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 256000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bufferInfo = MediaCodec.BufferInfo()
        var trackIndex = -1
        var muxerStarted = false
        var inputOffset = 0
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    inBuf.clear()
                    val remaining = pcmBytes.size - inputOffset
                    if (remaining <= 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val toCopy = minOf(inBuf.remaining(), remaining)
                        inBuf.put(pcmBytes, inputOffset, toCopy)
                        val ptsUs = (inputOffset.toLong() / 2L) * 1_000_000L / sampleRate.toLong()
                        codec.queueInputBuffer(inIdx, 0, toCopy, ptsUs, 0)
                        inputOffset += toCopy
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = muxer.addTrack(codec.outputFormat)
                muxer.start()
                muxerStarted = true
            } else if (outIdx >= 0) {
                val outBuf = codec.getOutputBuffer(outIdx)!!
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size > 0 && muxerStarted) {
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true
                }
            }
        }

        try {
            if (muxerStarted) muxer.stop()
        } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
        try { codec.stop(); codec.release() } catch (_: Exception) {}
    }

    /**
     * Universal Decoder: Decodes any audio URI (.flac, .wav, .mp3, .m4a, .aac, .ogg, .opus)
     * into 48kHz Mono float samples [-1.0, 1.0].
     */
    fun decodeAudioUriToMono48kHz(context: Context, uri: Uri): FloatArray {
        val tempFile = File(context.cacheDir, "import_audio_temp_${System.currentTimeMillis()}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            return decodeAudioFileToMono48kHz(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    fun decodeAudioFileToMono48kHz(file: File): FloatArray {
        // Fast path for standard 16-bit PCM WAV
        try {
            val head = ByteArray(12)
            file.inputStream().use { it.read(head) }
            if (String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WAVE") {
                return decodeWavFile(file)
            }
        } catch (_: Exception) {}

        // Universal Android MediaExtractor + MediaCodec path (supports FLAC, MP3, M4A/AAC, OGG, Opus, WAV)
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var audioTrack = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrack = i
                format = fmt
                break
            }
        }
        if (audioTrack < 0 || format == null) {
            extractor.release()
            throw IllegalArgumentException("No supported audio track found in ${file.name}")
        }

        extractor.selectTrack(audioTrack)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val srcSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else SAMPLE_RATE
        val srcChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmOut = ByteArrayOutputStream(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var inputEOF = false
        var outputEOF = false
        var outChannels = srcChannels
        var outSampleRate = srcSampleRate

        while (!outputEOF) {
            if (!inputEOF) {
                val inIdx = codec.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEOF = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(info, 10000)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFmt = codec.outputFormat
                if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outSampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outChannels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (outIdx >= 0) {
                val outBuf = codec.getOutputBuffer(outIdx)!!
                if (info.size > 0) {
                    val chunk = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    outBuf.get(chunk)
                    pcmOut.write(chunk)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputEOF = true
                }
            }
        }

        try { codec.stop(); codec.release() } catch (_: Exception) {}
        try { extractor.release() } catch (_: Exception) {}

        val rawBytes = pcmOut.toByteArray()
        val shortCount = rawBytes.size / 2
        val shortBuf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val monoFrames = shortCount / maxOf(1, outChannels)
        val mono = FloatArray(monoFrames)
        for (i in 0 until monoFrames) {
            var sum = 0f
            for (c in 0 until outChannels) {
                sum += shortBuf.get(i * outChannels + c) / 32768.0f
            }
            mono[i] = sum / outChannels
        }

        return if (outSampleRate == SAMPLE_RATE) mono else resampleLinear(mono, outSampleRate, SAMPLE_RATE)
    }

    private fun decodeWavFile(file: File): FloatArray {
        val allBytes = file.readBytes()
        if (allBytes.size <= 44) return FloatArray(0)
        val bb = ByteBuffer.wrap(allBytes).order(ByteOrder.LITTLE_ENDIAN)
        val channels = bb.getShort(22).toInt().coerceAtLeast(1)
        val sr = bb.getInt(24).coerceAtLeast(8000)
        var dataOffset = 44
        // Locate 'data' chunk
        var pos = 12
        while (pos + 8 <= allBytes.size) {
            val id = String(allBytes, pos, 4, Charsets.US_ASCII)
            val sz = bb.getInt(pos + 4)
            if (id == "data") {
                dataOffset = pos + 8
                break
            }
            pos += 8 + maxOf(0, sz)
        }
        val pcmLen = allBytes.size - dataOffset
        val totalShorts = pcmLen / 2
        val frames = totalShorts / channels
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val idx = dataOffset + (i * channels + c) * 2
                if (idx + 1 < allBytes.size) {
                    val s = ((allBytes[idx + 1].toInt() shl 8) or (allBytes[idx].toInt() and 0xFF)).toShort()
                    sum += s / 32768.0f
                }
            }
            mono[i] = sum / channels
        }
        return if (sr == SAMPLE_RATE) mono else resampleLinear(mono, sr, SAMPLE_RATE)
    }

    private fun resampleLinear(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (input.isEmpty() || fromRate == toRate) return input
        val outLen = ((input.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val ratio = fromRate.toDouble() / toRate.toDouble()
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, input.size - 1)
            val idx1 = (idx0 + 1).coerceIn(0, input.size - 1)
            val frac = (srcPos - idx0).toFloat()
            out[i] = input[idx0] * (1.0f - frac) + input[idx1] * frac
        }
        return out
    }

    fun floatToPcm16LeBytes(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i].coerceIn(-1.0f, 1.0f) * 32767.0f).roundToInt().coerceIn(-32768, 32767)
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }
}
