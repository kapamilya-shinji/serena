/*
 * Copyright (C) 2026 Shinji Sakiyama (shinjisakiyama@gmail.com)
 * Bayanihan • Magna Carta • Kapamilya
 * Licensed under the Apache License, Version 2.0
 */

package com.kapamilya.serena.phone

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * KapamilyaInCallService (v5.11.0)
 *
 * Android 公式標準電話アプリ（ROLE_DIALER）用 InCallService 実装。
 * - 世界4言語（日本語 / English / Tagalog / Español）完全ローカライズ対応
 * - 接続中サウンド・通話開始（入室）サウンド・通話終了（切断）サウンド自動再生
 * - 通話終了時に 1秒単位の正確な通話時間（時間・分・秒）を自動音声アナウンス！
 */
class KapamilyaInCallService : InCallService(), TextToSpeech.OnInitListener {

    interface CallStateObserver {
        fun onCallStateUpdated(call: Call?, state: Int, callerDisplay: String, connectedDurationMs: Long)
        fun onCallFinishedWithExactDuration(callerDisplay: String, durationMs: Long, formattedDuration: String)
    }

    companion object {
        private const val TAG = "KapamilyaInCallSvc"
        const val PREFS_NAME = "kapamilya_phone_prefs"
        const val KEY_UI_LANG = "ui_lang"

        @Volatile
        var instance: KapamilyaInCallService? = null
            private set

        @Volatile
        var activeCall: Call? = null
            private set

        @Volatile
        var callConnectedElapsedMs: Long = 0L
            private set

        private val observers = CopyOnWriteArrayList<CallStateObserver>()

        fun addObserver(observer: CallStateObserver) {
            if (!observers.contains(observer)) observers.add(observer)
        }

        fun removeObserver(observer: CallStateObserver) {
            observers.remove(observer)
        }

        fun resolveActiveLang(context: Context?): String {
            if (context != null) {
                val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_UI_LANG, null)
                if (!saved.isNullOrBlank()) return saved
            }
            val sys = Locale.getDefault().language.lowercase()
            return when {
                sys.startsWith("ja") -> "ja"
                sys.startsWith("tl") || sys.startsWith("fil") -> "tl"
                sys.startsWith("es") -> "es"
                else -> "en"
            }
        }

        fun formatExactDuration(durationMs: Long, lang: String = "ja"): String {
            val totalSeconds = (durationMs / 1000L).coerceAtLeast(1L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L

            return when (lang) {
                "en" -> {
                    val parts = mutableListOf<String>()
                    if (hours > 0) parts.add("$hours hours")
                    if (minutes > 0 || hours > 0) parts.add("$minutes minutes")
                    parts.add("$seconds seconds")
                    parts.joinToString(" ")
                }
                "tl" -> {
                    val parts = mutableListOf<String>()
                    if (hours > 0) parts.add("$hours oras")
                    if (minutes > 0 || hours > 0) parts.add("$minutes minuto")
                    parts.add("$seconds segundo")
                    parts.joinToString(", ")
                }
                "es" -> {
                    val parts = mutableListOf<String>()
                    if (hours > 0) parts.add("$hours horas")
                    if (minutes > 0 || hours > 0) parts.add("$minutes minutos")
                    parts.add("$seconds segundos")
                    parts.joinToString(" ")
                }
                else -> {
                    val parts = mutableListOf<String>()
                    if (hours > 0) parts.add("${hours}時間")
                    if (minutes > 0 || hours > 0) parts.add("${minutes}分")
                    parts.add("${seconds}秒")
                    parts.joinToString("")
                }
            }
        }

        fun formatExactDurationJa(durationMs: Long): String = formatExactDuration(durationMs, "ja")
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            handleCallStateChange(call, state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            tts = TextToSpeech(applicationContext, this)
        } catch (e: Exception) {
            Log.w(TAG, "TTS init error: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
                applyTtsLocale(resolveActiveLang(applicationContext))
                isTtsReady = true
            } catch (_: Exception) {}
        }
    }

    private fun applyTtsLocale(lang: String) {
        try {
            val loc = when (lang) {
                "en" -> Locale.US
                "tl" -> Locale("fil", "PH")
                "es" -> Locale("es", "ES")
                else -> Locale.JAPANESE
            }
            tts?.language = loc
        } catch (_: Exception) {}
    }

    fun speakAnnouncement(text: String) {
        if (!isTtsReady || text.isBlank()) return
        try {
            applyTtsLocale(resolveActiveLang(applicationContext))
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kapamilya_phone_${System.currentTimeMillis()}")
        } catch (_: Exception) {}
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        activeCall = call
        call.registerCallback(callCallback)
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) call.details.state else @Suppress("DEPRECATION") call.state
        handleCallStateChange(call, state)

        try {
            val intent = Intent(this, KapamilyaPhoneMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("FROM_INCALL_SERVICE", true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch KapamilyaPhoneMainActivity: ${e.message}")
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        val lang = resolveActiveLang(applicationContext)
        val caller = extractCallerDisplay(call, lang)
        val durationMs = if (callConnectedElapsedMs > 0L) {
            (SystemClock.elapsedRealtime() - callConnectedElapsedMs).coerceAtLeast(1000L)
        } else {
            val connectTimeMillis = call.details?.connectTimeMillis ?: 0L
            if (connectTimeMillis > 0L) (System.currentTimeMillis() - connectTimeMillis).coerceAtLeast(1000L) else 1000L
        }

        val formatted = formatExactDuration(durationMs, lang)
        KapamilyaPhoneSoundEngine.playCallDisconnectedSound {
            val msg = when (lang) {
                "en" -> "Call ended with $caller. Exact call duration: $formatted."
                "tl" -> "Tapos na ang tawag kay $caller. Eksaktong tagal ng tawag: $formatted."
                "es" -> "Llamada finalizada con $caller. Duración exacta: $formatted."
                else -> "${caller}との通話が終了しました。正確な通話時間は、${formatted}でした。"
            }
            speakAnnouncement(msg)
        }

        for (obs in observers) {
            obs.onCallFinishedWithExactDuration(caller, durationMs, formatted)
        }

        if (activeCall == call) {
            activeCall = null
            callConnectedElapsedMs = 0L
        }
    }

    private fun handleCallStateChange(call: Call, state: Int) {
        val lang = resolveActiveLang(applicationContext)
        val caller = extractCallerDisplay(call, lang)
        when (state) {
            Call.STATE_RINGING -> {
                callConnectedElapsedMs = 0L
                KapamilyaPhoneSoundEngine.playConnectingSound()
                speakAnnouncement(
                    when (lang) {
                        "en" -> "Incoming call on Kapamilya Serena Phone from $caller. Tap Answer to pick up."
                        "tl" -> "May tumatawag sa Kapamilya Serena Phone mula kay $caller. Pindutin ang Sagutin."
                        "es" -> "Llamada entrante en Kapamilya Serena Phone de $caller. Toque Responder."
                        else -> "Kapamilya Serena Phone 着信中。相手は ${caller} です。応答ボタンで電話に出られます。"
                    }
                )
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                callConnectedElapsedMs = 0L
                KapamilyaPhoneSoundEngine.playConnectingSound()
                speakAnnouncement(
                    when (lang) {
                        "en" -> "Calling $caller..."
                        "tl" -> "Tinatawagan si $caller..."
                        "es" -> "Llamando a $caller..."
                        else -> "${caller} へ発信・接続しています..."
                    }
                )
            }
            Call.STATE_ACTIVE -> {
                if (callConnectedElapsedMs == 0L) {
                    callConnectedElapsedMs = SystemClock.elapsedRealtime()
                    KapamilyaPhoneSoundEngine.playParticipantJoinedSound {
                        speakAnnouncement(
                            when (lang) {
                                "en" -> "Connected with $caller. Call duration timer started."
                                "tl" -> "Nakakonekta na kay $caller. Nagsimula na ang timer ng tawag."
                                "es" -> "Conectado con $caller. Temporizador de llamada iniciado."
                                else -> "${caller} と通話がつながりました。通話時間の計測を開始します。"
                            }
                        )
                    }
                }
            }
            Call.STATE_HOLDING -> {
                speakAnnouncement(
                    when (lang) {
                        "en" -> "Call placed on hold."
                        "tl" -> "Naka-hold ang tawag."
                        "es" -> "Llamada en espera."
                        else -> "通話を保留にしました。"
                    }
                )
            }
        }

        val currentDur = if (callConnectedElapsedMs > 0L) SystemClock.elapsedRealtime() - callConnectedElapsedMs else 0L
        for (obs in observers) {
            obs.onCallStateUpdated(call, state, caller, currentDur)
        }
    }

    fun answerActiveRingingCall() {
        val call = activeCall ?: return
        try {
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
        } catch (e: Exception) {
            Log.e(TAG, "answerActiveRingingCall error: ${e.message}")
        }
    }

    fun disconnectActiveCall() {
        val call = activeCall ?: return
        try {
            val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) call.details.state else @Suppress("DEPRECATION") call.state
            if (state == Call.STATE_RINGING) {
                call.reject(false, null)
            } else {
                call.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "disconnectActiveCall error: ${e.message}")
        }
    }

    fun sendDtmfDigit(digit: Char) {
        val call = activeCall ?: return
        try {
            call.playDtmfTone(digit)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { call.stopDtmfTone() } catch (_: Exception) {}
            }, 180L)
        } catch (e: Exception) {
            Log.w(TAG, "sendDtmfDigit error: ${e.message}")
        }
    }

    fun setSpeakerphoneEnabled(enabled: Boolean) {
        val lang = resolveActiveLang(applicationContext)
        try {
            val route = if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
            setAudioRoute(route)
            speakAnnouncement(
                when (lang) {
                    "en" -> if (enabled) "Speakerphone ON" else "Speakerphone OFF (Earpiece)"
                    "tl" -> if (enabled) "Naka-ON ang Speakerphone" else "Naka-OFF ang Speakerphone"
                    "es" -> if (enabled) "Altavoz activado" else "Altavoz desactivado"
                    else -> if (enabled) "スピーカーフォンをオンにしました" else "スピーカーフォンをオフ（受話口）にしました"
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "setSpeakerphoneEnabled error: ${e.message}")
        }
    }

    fun setMicMutedState(muted: Boolean) {
        val lang = resolveActiveLang(applicationContext)
        try {
            setMuted(muted)
            speakAnnouncement(
                when (lang) {
                    "en" -> if (muted) "Microphone muted" else "Microphone unmuted"
                    "tl" -> if (muted) "Naka-mute ang mikropono" else "Hindi na naka-mute ang mikropono"
                    "es" -> if (muted) "Micrófono silenciado" else "Micrófono activado"
                    else -> if (muted) "マイクをミュート（消音）にしました" else "マイクのミュートを解除しました"
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "setMicMutedState error: ${e.message}")
        }
    }

    private fun extractCallerDisplay(call: Call, lang: String): String {
        val unknown = when (lang) {
            "en" -> "Unknown caller"
            "tl" -> "Hindi kilalang tumatawag"
            "es" -> "Número desconocido"
            else -> "不明な相手"
        }
        val details = call.details ?: return unknown
        val name = details.callerDisplayName?.trim() ?: ""
        val handle = details.handle?.schemeSpecificPart?.trim() ?: ""
        return when {
            name.isNotEmpty() && handle.isNotEmpty() -> "$name ($handle)"
            name.isNotEmpty() -> name
            handle.isNotEmpty() -> handle
            else -> unknown
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
    }
}
