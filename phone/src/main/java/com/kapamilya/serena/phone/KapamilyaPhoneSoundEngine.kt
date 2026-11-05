/*
 * Copyright (C) 2026 Shinji Sakiyama (shinjisakiyama@gmail.com)
 * Bayanihan • Magna Carta • Kapamilya
 * Under the Banner of the Magna Carta & Bantay ng Dangal
 */

package com.kapamilya.serena.phone

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * KapamilyaPhoneSoundEngine (v5.11.0)
 *
 * 視覚障害者が電話・WebRTCグループ通話・AIテストボット通話の状態変化を直感的に把握するための
 * 純粋16-bit PCM 音響イヤーコン（効果音）シンセサイザーエンジン。
 *
 * 1. 接続中サウンド (Connecting / Ringback Tone):
 *    - 発信・ルーム接続・ボット呼び出し開始時の温かみのある2連クリスタル・リングバック音 (440Hz + 480Hz)
 * 2. 入った音 (Participant Joined / Connected Chime):
 *    - 通話接続完了時およびグループ通話にメンバーやAIボットが入室した時の明るい上昇4音ハーモニックチャイム
 *      (C5 523.25Hz -> E5 659.25Hz -> G5 783.99Hz -> C6 1046.50Hz)
 * 3. 落ちた音 (Participant Left / Dropped Chime & Call Disconnected Chime):
 *    - メンバーが退室・切断した時、または通話を終了した時の明確な下降ハーモニックチャイム
 *      (G5 783.99Hz -> Eb5 622.25Hz -> C5 523.25Hz / G4 392.00Hz)
 * 4. ボット自動聞き取り開始ピコン音 (Bot Auto-Listening Cue):
 *    - ボットが話し終えてユーザーのマイク音声を自動で聞き取り始めた合図のソフトな2音 (E5 -> A5)
 */
object KapamilyaPhoneSoundEngine {

    private const val SAMPLE_RATE = 44100
    private val soundExecutor = Executors.newSingleThreadExecutor()

    data class ToneSegment(
        val freq1Hz: Double,
        val freq2Hz: Double = 0.0,
        val durationMs: Int,
        val volume: Double = 0.48,
        val pauseAfterMs: Int = 25
    )

    /**
     * 1. 接続中サウンド（プルルル…に相当する心地よいクリスタル接続コール音）
     */
    fun playConnectingSound(onComplete: (() -> Unit)? = null) {
        val segments = listOf(
            ToneSegment(freq1Hz = 440.0, freq2Hz = 480.0, durationMs = 320, volume = 0.42, pauseAfterMs = 90),
            ToneSegment(freq1Hz = 440.0, freq2Hz = 480.0, durationMs = 320, volume = 0.42, pauseAfterMs = 60)
        )
        playSequenceAsync(segments, onComplete)
    }

    /**
     * 2. 入った音（通話接続完了・メンバー入室・ボット参加時の明るい上昇4音チャイム）
     */
    fun playParticipantJoinedSound(onComplete: (() -> Unit)? = null) {
        val segments = listOf(
            ToneSegment(freq1Hz = 523.25, freq2Hz = 1046.50, durationMs = 95, volume = 0.46, pauseAfterMs = 15),
            ToneSegment(freq1Hz = 659.25, freq2Hz = 1318.51, durationMs = 95, volume = 0.48, pauseAfterMs = 15),
            ToneSegment(freq1Hz = 783.99, freq2Hz = 1567.98, durationMs = 95, volume = 0.50, pauseAfterMs = 15),
            ToneSegment(freq1Hz = 1046.50, freq2Hz = 1567.98, durationMs = 220, volume = 0.54, pauseAfterMs = 40)
        )
        playSequenceAsync(segments, onComplete)
    }

    /**
     * 3. 落ちた音（メンバー退室・通信切断時の下降3音チャイム）
     */
    fun playParticipantLeftSound(onComplete: (() -> Unit)? = null) {
        val segments = listOf(
            ToneSegment(freq1Hz = 783.99, freq2Hz = 392.00, durationMs = 120, volume = 0.48, pauseAfterMs = 20),
            ToneSegment(freq1Hz = 622.25, freq2Hz = 311.13, durationMs = 120, volume = 0.46, pauseAfterMs = 20),
            ToneSegment(freq1Hz = 523.25, freq2Hz = 261.63, durationMs = 240, volume = 0.45, pauseAfterMs = 40)
        )
        playSequenceAsync(segments, onComplete)
    }

    /**
     * 4. 通話終了・切断サウンド（通話全体の終了を示す下降4音チャイム）
     */
    fun playCallDisconnectedSound(onComplete: (() -> Unit)? = null) {
        val segments = listOf(
            ToneSegment(freq1Hz = 783.99, freq2Hz = 1174.66, durationMs = 100, volume = 0.48, pauseAfterMs = 15),
            ToneSegment(freq1Hz = 659.25, freq2Hz = 987.77, durationMs = 100, volume = 0.46, pauseAfterMs = 15),
            ToneSegment(freq1Hz = 523.25, freq2Hz = 783.99, durationMs = 110, volume = 0.45, pauseAfterMs = 15),
            ToneSegment(freq1Hz = 392.00, freq2Hz = 523.25, durationMs = 250, volume = 0.48, pauseAfterMs = 40)
        )
        playSequenceAsync(segments, onComplete)
    }

    /**
     * 5. ボット自動音声認識開始キュー（「ピコン♪」とマイクが開いた合図）
     */
    fun playBotListeningCue(onComplete: (() -> Unit)? = null) {
        val segments = listOf(
            ToneSegment(freq1Hz = 659.25, freq2Hz = 1318.51, durationMs = 70, volume = 0.36, pauseAfterMs = 15),
            ToneSegment(freq1Hz = 880.00, freq2Hz = 1760.00, durationMs = 110, volume = 0.40, pauseAfterMs = 20)
        )
        playSequenceAsync(segments, onComplete)
    }

    private fun playSequenceAsync(segments: List<ToneSegment>, onComplete: (() -> Unit)?) {
        soundExecutor.execute {
            try {
                val pcmSamples = synthesizeSequence(segments)
                if (pcmSamples.isNotEmpty()) {
                    val byteCount = pcmSamples.size * 2
                    val minBuf = AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(byteCount)

                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()

                    track.write(pcmSamples, 0, pcmSamples.size)
                    track.play()
                    val totalDurationMs = (pcmSamples.size * 1000L) / SAMPLE_RATE
                    Thread.sleep(totalDurationMs + 40L)
                    try {
                        track.stop()
                    } catch (_: Exception) {}
                    try {
                        track.release()
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            } finally {
                onComplete?.invoke()
            }
        }
    }

    private fun synthesizeSequence(segments: List<ToneSegment>): ShortArray {
        var totalSamples = 0
        for (seg in segments) {
            totalSamples += (SAMPLE_RATE * (seg.durationMs + seg.pauseAfterMs)) / 1000
        }
        val out = ShortArray(totalSamples)
        var offset = 0

        for (seg in segments) {
            val toneSamples = (SAMPLE_RATE * seg.durationMs) / 1000
            val pauseSamples = (SAMPLE_RATE * seg.pauseAfterMs) / 1000
            val attackSamples = (SAMPLE_RATE * 10) / 1000
            val releaseSamples = (SAMPLE_RATE * 25) / 1000

            for (i in 0 until toneSamples) {
                if (offset >= out.size) break
                val t = i.toDouble() / SAMPLE_RATE.toDouble()
                val env = when {
                    i < attackSamples -> i.toDouble() / attackSamples.toDouble()
                    i > toneSamples - releaseSamples -> (toneSamples - i).toDouble() / releaseSamples.toDouble()
                    else -> exp(-2.2 * (i.toDouble() / toneSamples.toDouble()))
                }

                val w1 = sin(2.0 * PI * seg.freq1Hz * t)
                val w2 = if (seg.freq2Hz > 0.0) 0.45 * sin(2.0 * PI * seg.freq2Hz * t) else 0.0
                val norm = if (seg.freq2Hz > 0.0) 1.45 else 1.0
                val sample = ((w1 + w2) / norm) * seg.volume * env
                out[offset++] = (sample * 32000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            for (i in 0 until pauseSamples) {
                if (offset >= out.size) break
                out[offset++] = 0
            }
        }
        return out
    }
}
