package com.kapamilya.serena.recorder

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kapamilya Serena Recorder v5.4.0 — On-Device Vocal Pitch (F0) & Register Spectrum Analyzer
 * Uses Normalized Autocorrelation (NACF) with subharmonic rejection + Goertzel DFT harmonic analysis
 * (H1 fundamental vs H2/H3 overtones in dB) to accurately measure:
 * - Median & Mean Fundamental Frequency (F0 sweet spot)
 * - Lowest Chest Voice note & stable low range
 * - Core Chest & Upper Chest / Mix Passaggio range (with H1-H2 glottal closure metric)
 * - High Mix / Reinforced Head Voice ceiling (highest note before H1-H2 flips positive)
 * - Pure Head / Falsetto (M2) range & High Head / Whistle Register peak
 */
object KapamilyaVocalPitchAnalyzer {

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    data class VocalAnalysisReport(
        val voicedFrameCount: Int,
        val medianF0Hz: Double,
        val medianNote: String,
        val meanF0Hz: Double,
        val meanNote: String,
        val minSustainedHz: Double,
        val minSustainedNote: String,
        val stableLowHz: Double,
        val stableLowNote: String,
        val mixCeilingHz: Double,
        val mixCeilingNote: String,
        val headCeilingHz: Double,
        val headCeilingNote: String,
        val maxPeakHz: Double,
        val maxPeakNote: String,
        val chestRatioPct: Double,
        val mixRatioPct: Double,
        val headRatioPct: Double,
        val whistleRatioPct: Double,
        val chestH1H2Db: Double,
        val mixH1H2Db: Double,
        val headH1H2Db: Double,
        val whistleH1H2Db: Double,
        val spokenSummaryJa: String,
        val detailedTextReport: String
    )

    fun hzToNoteString(f: Double): String {
        if (f <= 0.0) return "-"
        val midi = (69.0 + 12.0 * (ln(f / 440.0) / ln(2.0))).roundToInt()
        val oct = (midi / 12) - 1
        val idx = ((midi % 12) + 12) % 12
        return String.format(Locale.US, "%s%d (%.1f Hz)", NOTE_NAMES[idx], oct, f)
    }

    fun analyzeVocalTrack(samples: FloatArray, sampleRate: Int = 48000): VocalAnalysisReport {
        if (samples.size < sampleRate) {
            val msg = "音声が短すぎるため、基本周波数とレジスター解析をスキップしました。"
            return VocalAnalysisReport(
                0, 0.0, "-", 0.0, "-", 0.0, "-", 0.0, "-", 0.0, "-", 0.0, "-", 0.0, "-",
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, msg, msg
            )
        }

        // Downsample 4x (e.g. 48kHz -> 12kHz) for fast, high-precision NACF pitch tracking
        val decim = max(1, sampleRate / 12000)
        val dsr = sampleRate / decim
        val dn = samples.size / decim
        val dx = FloatArray(dn)
        for (i in 0 until dn) {
            var s = 0f
            val base = i * decim
            for (k in 0 until decim) {
                s += samples[base + k]
            }
            dx[i] = s / decim
        }

        val frameLen = (dsr * 0.045).toInt() // 45ms window
        val hop = (dsr * 0.020).toInt()      // 20ms hop
        val minLag = max(2, (dsr / 1050.0).toInt()) // up to 1050 Hz (C6)
        val maxLag = min(frameLen - 2, (dsr / 70.0).toInt()) // down to 70 Hz (C#2)

        val rawF0 = ArrayList<Double>(2048)
        val rawH1H2 = ArrayList<Double>(2048)
        val rawTime = ArrayList<Double>(2048)

        val corr = DoubleArray(maxLag + 1)

        var start = 0
        while (start + frameLen < dn) {
            var sumSq = 0.0
            var zc = 0
            for (i in 0 until frameLen) {
                val s = dx[start + i]
                sumSq += s * s
                if (i > 0 && ((s >= 0f && dx[start + i - 1] < 0f) || (s < 0f && dx[start + i - 1] >= 0f))) {
                    zc++
                }
            }
            val rms = sqrt(sumSq / frameLen)
            val zcr = zc.toDouble() / frameLen.toDouble()
            if (rms >= 0.028 && zcr <= 0.22) {
                var bestLag = -1
                var bestCorr = -1.0
                val r0 = sumSq
                for (lag in minLag..maxLag) {
                    var sum = 0.0
                    var e2 = 0.0
                    val limit = frameLen - lag
                    for (i in 0 until limit) {
                        val a = dx[start + i].toDouble()
                        val b = dx[start + i + lag].toDouble()
                        sum += a * b
                        e2 += b * b
                    }
                    val c = sum / (sqrt(r0 * e2) + 1e-9)
                    corr[lag] = c
                    if (c > bestCorr) {
                        bestCorr = c
                        bestLag = lag
                    }
                }

                if (bestCorr >= 0.58 && bestLag > minLag) {
                    // Subharmonic guard: prefer shortest lag peak with >= 88% of bestCorr
                    var chosenLag = bestLag
                    for (lag in (minLag + 1) until bestLag) {
                        if (corr[lag] > corr[lag - 1] && corr[lag] >= corr[lag + 1] && corr[lag] >= bestCorr * 0.88) {
                            chosenLag = lag
                            break
                        }
                    }

                    val y1 = corr[max(minLag, chosenLag - 1)]
                    val y2 = corr[chosenLag]
                    val y3 = corr[min(maxLag, chosenLag + 1)]
                    var delta = 0.5 * (y1 - y3) / (y1 - 2.0 * y2 + y3 + 1e-12)
                    if (abs(delta) > 1.0) delta = 0.0
                    val exactLag = chosenLag + delta
                    val f0 = dsr.toDouble() / exactLag

                    val origStart = start * decim
                    val origLen = min(frameLen * decim, samples.size - origStart)
                    val h1 = goertzelMag(samples, origStart, origLen, sampleRate, f0)
                    val h2 = goertzelMag(samples, origStart, origLen, sampleRate, f0 * 2.0)
                    val h3 = goertzelMag(samples, origStart, origLen, sampleRate, f0 * 3.0)
                    val h1h2Db = 20.0 * log10((h1 + 1e-9) / (max(h2, h3 * 0.7) + 1e-9))

                    rawF0.add(f0)
                    rawH1H2.add(h1h2Db)
                    rawTime.add(start.toDouble() / dsr.toDouble())
                }
            }
            start += hop
        }

        // 3-consecutive-frame pitch stability filter (< 8% jump)
        val f0Clean = ArrayList<Double>(rawF0.size)
        val h1h2Clean = ArrayList<Double>(rawH1H2.size)
        for (i in 2 until rawF0.size - 2) {
            val c = rawF0[i]
            if (abs(c - rawF0[i - 1]) / c < 0.08 &&
                abs(c - rawF0[i + 1]) / c < 0.08 &&
                abs(rawTime[i + 1] - rawTime[i - 1]) < 0.08
            ) {
                f0Clean.add(c)
                h1h2Clean.add(rawH1H2[i])
            }
        }

        if (f0Clean.isEmpty()) {
            val msg = "有声ボーカル区間が検出されませんでした。"
            return VocalAnalysisReport(
                0, 0.0, "-", 0.0, "-", 0.0, "-", 0.0, "-", 0.0, "-", 0.0, "-", 0.0, "-",
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, msg, msg
            )
        }

        val sorted = f0Clean.toDoubleArray().apply { sort() }
        val n = sorted.size
        fun pct(p: Double): Double {
            val idx = ((n * p) / 100.0).toInt().coerceIn(0, n - 1)
            return sorted[idx]
        }

        val minSustained = sorted[0]
        val stableLow = pct(2.0)
        val medianF0 = pct(50.0)
        val meanF0 = sorted.average()
        val headCeiling = pct(99.2)
        val maxPeak = sorted[n - 1]

        // Register statistics & H1-H2 glottal closure tracking
        var chestCnt = 0; var chestH1H2Sum = 0.0
        var mixCnt = 0; var mixH1H2Sum = 0.0
        var headCnt = 0; var headH1H2Sum = 0.0
        var whistleCnt = 0; var whistleH1H2Sum = 0.0
        var highestAdductedMixHz = medianF0

        for (i in f0Clean.indices) {
            val f = f0Clean[i]
            val h = h1h2Clean[i]
            if (f < 220.0) {
                chestCnt++
                chestH1H2Sum += h
            } else if (f < 329.6) {
                mixCnt++
                mixH1H2Sum += h
                if (h <= 0.5 && f > highestAdductedMixHz) highestAdductedMixHz = f
            } else if (f < 523.3) {
                headCnt++
                headH1H2Sum += h
                if (h <= 0.5 && f > highestAdductedMixHz) highestAdductedMixHz = f
            } else {
                whistleCnt++
                whistleH1H2Sum += h
            }
        }

        val mixCeiling = max(highestAdductedMixHz, pct(95.0))
        val chestPct = 100.0 * chestCnt / n
        val mixPct = 100.0 * mixCnt / n
        val headPct = 100.0 * headCnt / n
        val whistlePct = 100.0 * whistleCnt / n

        val chestDb = if (chestCnt > 0) chestH1H2Sum / chestCnt else 0.0
        val mixDb = if (mixCnt > 0) mixH1H2Sum / mixCnt else 0.0
        val headDb = if (headCnt > 0) headH1H2Sum / headCnt else 0.0
        val whistleDb = if (whistleCnt > 0) whistleH1H2Sum / whistleCnt else 0.0

        val minNote = hzToNoteString(minSustained)
        val stableLowNote = hzToNoteString(stableLow)
        val medianNote = hzToNoteString(medianF0)
        val meanNote = hzToNoteString(meanF0)
        val mixCeilNote = hzToNoteString(mixCeiling)
        val headCeilNote = hzToNoteString(headCeiling)
        val maxPeakNote = hzToNoteString(maxPeak)

        val spoken = buildString {
            append("ボーカルスペクトラム＆音域レジスター解析レポートだよ！")
            append("歌の基本周波数の中心（中央値）は $medianNote、平均は $meanNote。")
            append("チェストボイス（地声）の最低到達音は $minNote（安定フレーズ下限は $stableLowNote）。")
            append("声帯閉鎖を保ったミックスボイスの限界上限は $mixCeilNote（倍音比 H1マイナスH2 は ${String.format(Locale.US, "%+.1f", mixDb)} デシベル）。")
            append("純ヘッドボイス（ファルセット）の安定到達ラインは $headCeilNote、")
            append("そしてハイヘッド・ホイッスル領域の最高到達ピークは $maxPeakNote だよ！")
        }

        val detailed = buildString {
            appendLine("==================================================================")
            appendLine("Kapamilya Serena Recorder v5.4.0 — Vocal Spectrum & Register Report")
            appendLine("Analyzed Sustained Voiced Frames: $n")
            appendLine("==================================================================")
            appendLine("1. Fundamental Frequency (F0) Center (基本周波数の中心・スイートスポット)")
            appendLine("   - Median F0 (中央値 / 50%) : $medianNote")
            appendLine("   - Mean F0   (平均値)       : $meanNote")
            appendLine()
            appendLine("2. Vocal Register Boundaries (チェスト / ミックス / ヘッド / ホイッスル限界)")
            appendLine("   - Chest Voice Bottom (地声最低到達音)           : $minNote (安定下限: $stableLowNote)")
            appendLine("   - Adducted Mix / Head Ceiling (芯のあるミックス上限) : $mixCeilNote")
            appendLine("   - Pure Head / Falsetto Ceiling (純ヘッド安定上限)    : $headCeilNote")
            appendLine("   - High Head / Whistle Peak (ホイッスル領域最高到達音): $maxPeakNote")
            appendLine()
            appendLine("3. Register Distribution & H1-H2 Harmonic Closure (倍音比 H1-H2 < 0dB = 声門閉鎖実声)")
            appendLine(String.format(Locale.US, "   - Chest (<A3 / 220Hz)        : %5.1f%% | Mean H1-H2 = %+5.1f dB", chestPct, chestDb))
            appendLine(String.format(Locale.US, "   - Mix Passaggio (A3-E4)      : %5.1f%% | Mean H1-H2 = %+5.1f dB", mixPct, mixDb))
            appendLine(String.format(Locale.US, "   - High Mix / Head (E4-C5)    : %5.1f%% | Mean H1-H2 = %+5.1f dB", headPct, headDb))
            appendLine(String.format(Locale.US, "   - Pure Head / Whistle (C5+)  : %5.1f%% | Mean H1-H2 = %+5.1f dB", whistlePct, whistleDb))
        }

        return VocalAnalysisReport(
            voicedFrameCount = n,
            medianF0Hz = medianF0,
            medianNote = medianNote,
            meanF0Hz = meanF0,
            meanNote = meanNote,
            minSustainedHz = minSustained,
            minSustainedNote = minNote,
            stableLowHz = stableLow,
            stableLowNote = stableLowNote,
            mixCeilingHz = mixCeiling,
            mixCeilingNote = mixCeilNote,
            headCeilingHz = headCeiling,
            headCeilingNote = headCeilNote,
            maxPeakHz = maxPeak,
            maxPeakNote = maxPeakNote,
            chestRatioPct = chestPct,
            mixRatioPct = mixPct,
            headRatioPct = headPct,
            whistleRatioPct = whistlePct,
            chestH1H2Db = chestDb,
            mixH1H2Db = mixDb,
            headH1H2Db = headDb,
            whistleH1H2Db = whistleDb,
            spokenSummaryJa = spoken,
            detailedTextReport = detailed
        )
    }

    private fun goertzelMag(x: FloatArray, start: Int, len: Int, sr: Int, targetFreq: Double): Double {
        if (len <= 2 || targetFreq <= 0.0 || targetFreq >= sr * 0.48) return 0.0
        var re = 0.0
        var im = 0.0
        val w = 2.0 * PI * targetFreq / sr.toDouble()
        val denom = (len - 1).toDouble()
        for (i in 0 until len) {
            val h = 0.5 - 0.5 * cos(2.0 * PI * i / denom)
            val v = x[start + i] * h
            re += v * cos(w * i)
            im -= v * sin(w * i)
        }
        return sqrt(re * re + im * im)
    }
}
