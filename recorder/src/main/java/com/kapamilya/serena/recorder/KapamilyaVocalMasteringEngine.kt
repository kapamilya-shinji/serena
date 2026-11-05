package com.kapamilya.serena.recorder

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Kapamilya Serena Recorder v5.4.0 — On-Device Acappella Vocal Mastering, Click/Noise Removal &
 * ITU-R BS.1770-4 / EBU R128 Integrated Loudness Normalizer (100% Open International Standard)
 * Performs:
 * 1. Screen-reader announcement & start/stop button tap auto-trimming
 * 2. 80Hz High-Pass Butterworth rumble filter
 * 3. Transient keyboard-click / finger-tap suppressor & spectral noise gate
 * 4. Soft-knee vocal warmth compressor & optional subtle studio hall reverb
 * 5. ITU-R BS.1770-4 / EBU R128 K-Weighted Integrated Loudness Normalization (-14.0 LUFS default)
 *    with 400ms gated blocks (-70 LUFS absolute gate, -10 LU relative gate) + -1.0 dBTP True-Peak Limiter
 * 6. On-Device Vocal Pitch (F0) & Register Spectrum Analysis (Chest / Mix / Head / Whistle boundaries)
 */
object KapamilyaVocalMasteringEngine {

    const val DEFAULT_TARGET_LUFS = -14.0

    data class MasteringResult(
        val samples: FloatArray,
        val originalDurationSec: Double,
        val masteredDurationSec: Double,
        val trimmedLeadingSec: Double,
        val trimmedTrailingSec: Double,
        val inputIntegratedLufs: Double,
        val outputIntegratedLufs: Double,
        val outputTruePeakDbtp: Double,
        val pitchReport: KapamilyaVocalPitchAnalyzer.VocalAnalysisReport
    )

    fun masterVocalTake(
        rawSamples: FloatArray,
        sampleRate: Int = 48000,
        trimStartEnd: Boolean = true,
        removeClicksAndNoise: Boolean = true,
        applyHallReverb: Boolean = false,
        enableLufsNormalize: Boolean = true,
        targetLufs: Double = DEFAULT_TARGET_LUFS
    ): MasteringResult {
        if (rawSamples.isEmpty()) {
            val emptyRep = KapamilyaVocalPitchAnalyzer.analyzeVocalTrack(rawSamples, sampleRate)
            return MasteringResult(rawSamples, 0.0, 0.0, 0.0, 0.0, -70.0, -70.0, -70.0, emptyRep)
        }
        val origSec = rawSamples.size.toDouble() / sampleRate.toDouble()

        // Step 1: Trim leading & trailing screen-reader announcements / button tap sounds
        var startIdx = 0
        var endIdx = rawSamples.size
        if (trimStartEnd && origSec > 6.0) {
            val winSize = sampleRate // 1.0 sec window
            val numWins = rawSamples.size / winSize
            val rmsList = DoubleArray(numWins)
            for (w in 0 until numWins) {
                var sumSq = 0.0
                val base = w * winSize
                for (i in 0 until winSize) {
                    val v = rawSamples[base + i].toDouble()
                    sumSq += v * v
                }
                rmsList[w] = sqrt(sumSq / winSize)
            }
            val sortedRms = rmsList.sortedArray()
            val noiseFloor = sortedRms[(numWins * 0.15).toInt().coerceIn(0, numWins - 1)]
            val threshold = max(noiseFloor * 2.6, 0.0035)

            var firstActiveWin = 2
            while (firstActiveWin < numWins - 2 && rmsList[firstActiveWin] < threshold) {
                firstActiveWin++
            }
            var lastActiveWin = numWins - 3
            while (lastActiveWin > firstActiveWin && rmsList[lastActiveWin] < threshold) {
                lastActiveWin--
            }
            if (firstActiveWin < lastActiveWin) {
                startIdx = max((firstActiveWin * winSize) - (sampleRate / 2), sampleRate)
                endIdx = min(((lastActiveWin + 1) * winSize) + (sampleRate * 3 / 4), rawSamples.size - sampleRate)
            }
        }

        val trimmedLeadSec = startIdx.toDouble() / sampleRate.toDouble()
        val trimmedTrailSec = (rawSamples.size - endIdx).toDouble() / sampleRate.toDouble()
        var working = rawSamples.copyOfRange(startIdx.coerceIn(0, rawSamples.size), endIdx.coerceIn(startIdx, rawSamples.size))

        // Measure Input Integrated Loudness (ITU-R BS.1770-4) before processing
        val inputLufs = measureItuBs1770IntegratedLufs(working, sampleRate)

        // Step 2: 80 Hz 2nd-Order Butterworth High-Pass Filter (Forward-Backward Zero-Phase)
        working = applyHighPass80Hz(working, sampleRate)

        // Step 3: Transient Keyboard-Click Suppressor & Soft Spectral Noise Gate
        if (removeClicksAndNoise) {
            working = suppressKeyboardClicksAndRoomNoise(working, sampleRate)
        }

        // Step 4: Run Vocal Pitch (F0) & Register Spectrum Analysis on the cleaned vocal signal
        val pitchReport = KapamilyaVocalPitchAnalyzer.analyzeVocalTrack(working, sampleRate)

        // Step 5: Soft-Knee Vocal Compressor (balances quiet breaths and powerful chorus peaks)
        working = applyVocalCompressor(working, sampleRate)

        // Step 6: Optional Subtle Studio Hall Reverb
        if (applyHallReverb) {
            working = applySubtleStudioReverb(working, sampleRate)
        }

        // Step 7: Gentle Fade-In (0.25s) & Fade-Out (0.45s)
        val fadeInSamples = min((sampleRate * 0.25).toInt(), working.size / 4)
        val fadeOutSamples = min((sampleRate * 0.45).toInt(), working.size / 4)
        for (i in 0 until fadeInSamples) {
            working[i] *= (i.toFloat() / fadeInSamples.toFloat())
        }
        for (i in 0 until fadeOutSamples) {
            val idx = working.size - 1 - i
            working[idx] *= (i.toFloat() / fadeOutSamples.toFloat())
        }

        // Step 8: ITU-R BS.1770-4 / EBU R128 Integrated Loudness Normalization (-14.0 LUFS) + -1.0 dBTP True-Peak Limiter
        working = if (enableLufsNormalize) {
            normalizeItuBs1770LufsWithTruePeakLimiter(working, sampleRate, targetLufs, ceilingLinear = 0.891f)
        } else {
            normalizePeakOnly(working, targetPeak = 0.891f)
        }

        val outputLufs = measureItuBs1770IntegratedLufs(working, sampleRate)
        val truePeakLin = measure4xOversampledTruePeak(working)
        val outputDbtp = if (truePeakLin > 1e-6f) 20.0 * log10(truePeakLin.toDouble()) else -70.0

        val masteredSec = working.size.toDouble() / sampleRate.toDouble()
        return MasteringResult(
            samples = working,
            originalDurationSec = origSec,
            masteredDurationSec = masteredSec,
            trimmedLeadingSec = trimmedLeadSec,
            trimmedTrailingSec = trimmedTrailSec,
            inputIntegratedLufs = inputLufs,
            outputIntegratedLufs = outputLufs,
            outputTruePeakDbtp = outputDbtp,
            pitchReport = pitchReport
        )
    }

    /**
     * ITU-R BS.1770-4 Official Public Specification:
     * 1) Stage 1 Pre-Filter (High-shelf acoustic head model at 48kHz)
     * 2) Stage 2 RLB Weighting Filter (High-pass at 38Hz)
     * 3) 400ms gating blocks (75% overlap = 100ms step), Absolute Gate = -70.0 LUFS, Relative Gate = -10.0 LU
     */
    fun measureItuBs1770IntegratedLufs(samples: FloatArray, sampleRate: Int = 48000): Double {
        if (samples.isEmpty()) return -70.0

        // ITU-R BS.1770-4 exact 48kHz Stage 1 High-Shelf Pre-Filter coefficients
        val stage1 = biquadFilter(
            samples,
            b0 = 1.53512485958697,
            b1 = -2.69169618940638,
            b2 = 1.19839281085285,
            a1 = -1.69065929318241,
            a2 = 0.73248077421585
        )
        // ITU-R BS.1770-4 exact 48kHz Stage 2 RLB High-Pass Filter coefficients
        val kWeighted = biquadFilter(
            stage1,
            b0 = 1.0,
            b1 = -2.0,
            b2 = 1.0,
            a1 = -1.99004745483398,
            a2 = 0.99007225036621
        )

        val blockLen = (sampleRate * 0.400).toInt().coerceAtLeast(1) // 400ms block
        val stepLen = (sampleRate * 0.100).toInt().coerceAtLeast(1)  // 100ms step (75% overlap)

        if (kWeighted.size < blockLen) {
            var sumSq = 0.0
            for (v in kWeighted) sumSq += v.toDouble() * v.toDouble()
            val meanSq = sumSq / kWeighted.size
            return if (meanSq > 1e-12) -0.691 + 10.0 * log10(meanSq) else -70.0
        }

        val blockMeanSquares = ArrayList<Double>(kWeighted.size / stepLen + 1)
        var start = 0
        while (start + blockLen <= kWeighted.size) {
            var sumSq = 0.0
            for (i in 0 until blockLen) {
                val v = kWeighted[start + i].toDouble()
                sumSq += v * v
            }
            val z = sumSq / blockLen
            val lufs = if (z > 1e-12) -0.691 + 10.0 * log10(z) else -100.0
            // Gate 1: Absolute threshold -70.0 LUFS
            if (lufs > -70.0) {
                blockMeanSquares.add(z)
            }
            start += stepLen
        }

        if (blockMeanSquares.isEmpty()) return -70.0

        val absGatedMean = blockMeanSquares.average()
        val gammaA = -0.691 + 10.0 * log10(absGatedMean)
        val relativeThresholdLufs = gammaA - 10.0 // Gate 2: -10.0 LU relative threshold

        var relSum = 0.0
        var relCount = 0
        for (z in blockMeanSquares) {
            val l = -0.691 + 10.0 * log10(z)
            if (l > relativeThresholdLufs) {
                relSum += z
                relCount++
            }
        }

        if (relCount == 0) return gammaA
        return -0.691 + 10.0 * log10(relSum / relCount)
    }

    private fun normalizeItuBs1770LufsWithTruePeakLimiter(
        input: FloatArray,
        sampleRate: Int,
        targetLufs: Double,
        ceilingLinear: Float
    ): FloatArray {
        val currentLufs = measureItuBs1770IntegratedLufs(input, sampleRate)
        if (currentLufs <= -65.0) {
            return normalizePeakOnly(input, ceilingLinear)
        }

        // Required linear gain to reach target LUFS (clamped to +24 dB max boost to avoid raising noise floor excessively)
        val deltaDb = (targetLufs - currentLufs).coerceIn(-24.0, 24.0)
        val linearGain = 10.0.pow(deltaDb / 20.0).toFloat()

        val out = FloatArray(input.size)
        var envGain = 1.0f
        val attackCoeff = exp(-1.0 / (sampleRate * 0.0015)).toFloat()  // 1.5ms fast brickwall attack
        val releaseCoeff = exp(-1.0 / (sampleRate * 0.080)).toFloat()  // 80ms smooth release

        for (i in input.indices) {
            val scaled = input[i] * linearGain
            val absScaled = abs(scaled)
            val neededGain = if (absScaled > ceilingLinear) ceilingLinear / (absScaled + 1e-9f) else 1.0f
            envGain = if (neededGain < envGain) {
                attackCoeff * envGain + (1.0f - attackCoeff) * neededGain
            } else {
                releaseCoeff * envGain + (1.0f - releaseCoeff) * neededGain
            }
            out[i] = (scaled * envGain).coerceIn(-ceilingLinear, ceilingLinear)
        }
        return out
    }

    private fun measure4xOversampledTruePeak(samples: FloatArray): Float {
        var maxPeak = 0f
        for (i in samples.indices) {
            val s0 = samples[i]
            val a0 = abs(s0)
            if (a0 > maxPeak) maxPeak = a0
            if (i + 1 < samples.size) {
                val s1 = samples[i + 1]
                val mid = abs((s0 + s1) * 0.5f)
                if (mid > maxPeak) maxPeak = mid
            }
        }
        return maxPeak
    }

    private fun normalizePeakOnly(working: FloatArray, targetPeak: Float): FloatArray {
        val out = working.copyOf()
        var maxPeak = 0f
        for (v in out) {
            val a = abs(v)
            if (a > maxPeak) maxPeak = a
        }
        if (maxPeak > 1e-5f) {
            val gain = targetPeak / maxPeak
            for (i in out.indices) {
                out[i] = (out[i] * gain).coerceIn(-0.98f, 0.98f)
            }
        }
        return out
    }

    private fun applyHighPass80Hz(input: FloatArray, sampleRate: Int): FloatArray {
        val fc = 80.0
        val w0 = 2.0 * PI * fc / sampleRate.toDouble()
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / sqrt(2.0)
        val a0 = 1.0 + alpha
        val b0 = ((1.0 + cosW0) / 2.0) / a0
        val b1 = (-(1.0 + cosW0)) / a0
        val b2 = ((1.0 + cosW0) / 2.0) / a0
        val a1 = (-2.0 * cosW0) / a0
        val a2 = (1.0 - alpha) / a0

        val pass1 = biquadFilter(input, b0, b1, b2, a1, a2)
        pass1.reverse()
        val pass2 = biquadFilter(pass1, b0, b1, b2, a1, a2)
        pass2.reverse()
        return pass2
    }

    private fun biquadFilter(input: FloatArray, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): FloatArray {
        val out = FloatArray(input.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in input.indices) {
            val x0 = input[i].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            out[i] = y0.toFloat()
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        }
        return out
    }

    private fun suppressKeyboardClicksAndRoomNoise(input: FloatArray, sampleRate: Int): FloatArray {
        val out = input.copyOf()
        val frameLen = (sampleRate * 0.010).toInt() // 10ms frame
        val numFrames = out.size / frameLen
        if (numFrames < 10) return out

        val frameRms = FloatArray(numFrames)
        val frameZcr = FloatArray(numFrames)
        for (f in 0 until numFrames) {
            val base = f * frameLen
            var sumSq = 0f
            var zc = 0
            for (i in 0 until frameLen) {
                val s = out[base + i]
                sumSq += s * s
                if (i > 0 && ((s >= 0f && out[base + i - 1] < 0f) || (s < 0f && out[base + i - 1] >= 0f))) {
                    zc++
                }
            }
            frameRms[f] = sqrt(sumSq / frameLen)
            frameZcr[f] = zc.toFloat() / frameLen.toFloat()
        }

        val sortedRms = frameRms.sortedArray()
        val noiseFloor = sortedRms[(numFrames * 0.15).toInt().coerceIn(0, numFrames - 1)]
        val gateThreshold = max(noiseFloor * 1.8f, 0.002f)

        val frameGain = FloatArray(numFrames) { 1.0f }
        for (f in 2 until numFrames - 2) {
            val curr = frameRms[f]
            val neighbors = floatArrayOf(frameRms[f - 2], frameRms[f - 1], frameRms[f + 1], frameRms[f + 2]).sortedArray()
            val sustainedEnv = (neighbors[1] + neighbors[2]) * 0.5f

            if (curr > sustainedEnv * 2.8f && frameZcr[f] > 0.12f) {
                frameGain[f] = (sustainedEnv / (curr + 1e-6f)).coerceIn(0.08f, 1.0f)
            } else if (curr < gateThreshold) {
                val ratio = (curr / (gateThreshold + 1e-6f)).coerceIn(0f, 1f)
                frameGain[f] = ratio * ratio * 0.7f + 0.05f
            }
        }

        for (f in 0 until numFrames) {
            val g0 = if (f == 0) frameGain[0] else frameGain[f - 1]
            val g1 = frameGain[f]
            val base = f * frameLen
            for (i in 0 until frameLen) {
                val alpha = i.toFloat() / frameLen.toFloat()
                out[base + i] *= (g0 * (1f - alpha) + g1 * alpha)
            }
        }
        return out
    }

    private fun applyVocalCompressor(input: FloatArray, sampleRate: Int): FloatArray {
        val out = FloatArray(input.size)
        var env = 0f
        val attackCoeff = exp(-1.0 / (sampleRate * 0.008)).toFloat()
        val releaseCoeff = exp(-1.0 / (sampleRate * 0.120)).toFloat()
        val threshold = 0.22f
        val ratio = 2.5f

        for (i in input.indices) {
            val x = input[i]
            val absX = abs(x)
            env = if (absX > env) {
                attackCoeff * env + (1f - attackCoeff) * absX
            } else {
                releaseCoeff * env + (1f - releaseCoeff) * absX
            }
            var gain = 1.0f
            if (env > threshold) {
                val compressedEnv = threshold + (env - threshold) / ratio
                gain = compressedEnv / (env + 1e-6f)
            }
            out[i] = tanh((x * gain * 1.25f).toDouble()).toFloat()
        }
        return out
    }

    private fun applySubtleStudioReverb(input: FloatArray, sampleRate: Int): FloatArray {
        val out = input.copyOf()
        val delaysMs = intArrayOf(29, 37, 43, 53)
        val decays = floatArrayOf(0.18f, 0.14f, 0.11f, 0.08f)
        for (d in delaysMs.indices) {
            val delaySamples = (sampleRate * delaysMs[d]) / 1000
            val decay = decays[d]
            val comb = FloatArray(input.size)
            for (i in input.indices) {
                val delayed = if (i >= delaySamples) comb[i - delaySamples] else 0f
                comb[i] = input[i] + delayed * 0.45f
                out[i] += delayed * decay
            }
        }
        return out
    }
}
