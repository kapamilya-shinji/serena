/*
 * Copyright (C) 2026 Shinji Sakiyama (shinjisakiyama@gmail.com)
 * Bayanihan • Magna Carta • Kapamilya
 * Under the Banner of the Magna Carta & Bantay ng Dangal
 */

package com.kapamilya.serena.phone

import android.util.Base64
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * KapamilyaAes256GcmMeshEngine (v5.11.0 — 4-Language Global Edition: ja / en / tl / es)
 *
 * 1. 全通信・全シグナリング・全招待コード・AI表情メタデータの完全 AES-256-GCM 認証付き暗号化
 *    - 鍵導出: PBKDF2-HMAC-SHA256 (100,000反復, 256-bit鍵)
 *    - 暗号方式: AES/GCM/NoPadding (96-bit / 12-byte SecureRandom IV + 128-bit / 16-byte 認証タグ + AAD検証)
 *    - 短縮安全確認コード (SAS Fingerprint): SHA-256 鍵ハッシュの先頭4バイトを `XXXX-XXXX` 形式で音声案内
 * 2. フルメッシュ P2P グループ通話管理:
 *    - ビデオ通話: 最大 6人 (`MAX_VIDEO_PARTICIPANTS = 6`)
 *    - 音声通話: 最大 10人 (`MAX_AUDIO_PARTICIPANTS = 10`)
 * 3. 入った音（入室チャイム）・落ちた音（退室チャイム）連動 ＆ 世界4言語完全対応 AIテストボット自動会話エンジン
 */
class KapamilyaAes256GcmMeshEngine(
    private val localDisplayName: String,
    private val langProvider: () -> String = { "ja" },
    private val onMeshEvent: (String) -> Unit,
    private val onPartnerExpressionReceived: (participantName: String, expressionText: String) -> Unit
) {

    companion object {
        private const val TAG = "KapamilyaAes256GcmMesh"
        const val MAX_VIDEO_PARTICIPANTS = 6
        const val MAX_AUDIO_PARTICIPANTS = 10
        const val MESH_UDP_PORT = 45882
        private const val PBKDF2_ITERATIONS = 100_000
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val WIRE_PREFIX = "KSP59_GCM:"
    }

    data class MeshParticipant(
        val peerId: String,
        var displayName: String,
        var endpoint: String,
        var isVideoEnabled: Boolean,
        var lastSeenMs: Long,
        var latestExpressionSummary: String
    )

    private val secureRandom = SecureRandom()
    private val participants = ConcurrentHashMap<String, MeshParticipant>()
    private val ioExecutor = Executors.newCachedThreadPool()

    @Volatile
    private var currentRoomName: String = "kapamilya-serena-room"

    @Volatile
    private var currentPassphrase: String = "Kapamilya-Serena-Secret-Key"

    @Volatile
    private var currentAesKey: SecretKey? = null

    @Volatile
    private var currentKeyFingerprint: String = "----"

    @Volatile
    private var isVideoRoomMode: Boolean = true

    @Volatile
    private var isRunning: Boolean = false

    private var udpSocket: DatagramSocket? = null
    private val localPeerId: String = "AND-" + (1000 + secureRandom.nextInt(9000))

    init {
        configureRoomAndDeriveKey(currentRoomName, currentPassphrase, isVideo = true)
    }

    private fun selfLabel(lang: String): String = when (lang) {
        "en" -> "$localDisplayName (You)"
        "tl" -> "$localDisplayName (Ikaw)"
        "es" -> "$localDisplayName (Tú)"
        else -> "$localDisplayName (自分)"
    }

    private fun selfInitialExp(lang: String): String = when (lang) {
        "en" -> "Centered in camera • Active"
        "tl" -> "Nasa gitna ng camera • Aktibo"
        "es" -> "Centrado en cámara • Activo"
        else -> "カメラ正面・参加中"
    }

    @Synchronized
    fun configureRoomAndDeriveKey(roomName: String, passphrase: String, isVideo: Boolean): String {
        val lang = langProvider()
        currentRoomName = roomName.trim().ifEmpty { "kapamilya-serena-room" }
        currentPassphrase = passphrase.trim().ifEmpty { currentRoomName }
        isVideoRoomMode = isVideo

        val saltStr = "KapamilyaSerenaPhone_AES256GCM_Salt_v5.9:$currentRoomName"
        val saltBytes = saltStr.toByteArray(StandardCharsets.UTF_8)
        val spec = PBEKeySpec(
            currentPassphrase.toCharArray(),
            saltBytes,
            PBKDF2_ITERATIONS,
            256
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val rawKeyBytes = factory.generateSecret(spec).encoded
        currentAesKey = SecretKeySpec(rawKeyBytes, "AES")

        val sha256 = MessageDigest.getInstance("SHA-256").digest(rawKeyBytes)
        currentKeyFingerprint = String.format(
            "%02X%02X-%02X%02X",
            sha256[0].toInt() and 0xFF,
            sha256[1].toInt() and 0xFF,
            sha256[2].toInt() and 0xFF,
            sha256[3].toInt() and 0xFF
        )

        participants.clear()
        participants[localPeerId] = MeshParticipant(
            peerId = localPeerId,
            displayName = selfLabel(lang),
            endpoint = "local",
            isVideoEnabled = isVideo,
            lastSeenMs = System.currentTimeMillis(),
            latestExpressionSummary = selfInitialExp(lang)
        )

        return currentKeyFingerprint
    }

    fun getKeyFingerprint(): String = currentKeyFingerprint

    fun getMaxParticipantsForCurrentMode(): Int =
        if (isVideoRoomMode) MAX_VIDEO_PARTICIPANTS else MAX_AUDIO_PARTICIPANTS

    fun getActiveParticipantCount(): Int = participants.size.coerceAtLeast(1)

    fun getParticipantsList(): List<MeshParticipant> =
        participants.values.sortedBy { it.peerId }

    fun encryptAes256Gcm(plainText: String): String {
        val key = currentAesKey ?: throw IllegalStateException("AES-256-GCM key not initialized")
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

        val aad = "KAPAMILYA_E2EE_V59:$currentRoomName".toByteArray(StandardCharsets.UTF_8)
        cipher.updateAAD(aad)

        val cipherBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)

        return WIRE_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptAes256Gcm(wirePayload: String): String? {
        val key = currentAesKey ?: return null
        val trimmed = wirePayload.trim()
        if (!trimmed.startsWith(WIRE_PREFIX)) return null

        return try {
            val combined = Base64.decode(trimmed.removePrefix(WIRE_PREFIX), Base64.NO_WRAP)
            if (combined.size < GCM_IV_LENGTH + 16) return null

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

            val aad = "KAPAMILYA_E2EE_V59:$currentRoomName".toByteArray(StandardCharsets.UTF_8)
            cipher.updateAAD(aad)

            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "AES-256-GCM authentication/decryption rejected packet: ${e.message}")
            null
        }
    }

    fun createEncryptedGroupInviteCode(): String {
        val maxCap = getMaxParticipantsForCurrentMode()
        val rawOffer = "KAPAMILYA-MESH-INVITE|v=5.11.0|room=$currentRoomName|mode=${if (isVideoRoomMode) "VIDEO_MAX6" else "AUDIO_MAX10"}|cap=${getActiveParticipantCount()}/$maxCap|peer=$localPeerId|name=$localDisplayName|sas=$currentKeyFingerprint|ts=${System.currentTimeMillis()}"
        return encryptAes256Gcm(rawOffer)
    }

    fun acceptEncryptedGroupInviteCode(encryptedCode: String): Pair<Boolean, String> {
        val lang = langProvider()
        val decrypted = decryptAes256Gcm(encryptedCode)
            ?: return false to when (lang) {
                "en" -> "AES-256-GCM Authentication Failed: Passphrase mismatch or tampered invite code rejected."
                "tl" -> "AES-256-GCM Error sa Autentikasyon: Mali ang passphrase o binago ang invite code."
                "es" -> "Error de autenticación AES-256-GCM: Clave incorrecta o código modificado rechazado."
                else -> "AES-256-GCM 認証エラー：合言葉（暗号鍵）が異なるか、招待コードが改ざんされているため拒否しました。"
            }

        var remotePeer = "PEER-" + (1000 + secureRandom.nextInt(9000))
        var remoteName = "Kapamilya Member"
        var remoteVideo = isVideoRoomMode

        for (part in decrypted.split("|")) {
            when {
                part.startsWith("peer=") -> remotePeer = part.removePrefix("peer=")
                part.startsWith("name=") -> remoteName = part.removePrefix("name=")
                part.startsWith("mode=") -> remoteVideo = part.contains("VIDEO")
            }
        }

        val addedMsg = registerOrUpdateRemoteParticipant(remotePeer, remoteName, "Invited-P2P", remoteVideo)
        return true to addedMsg
    }

    @Synchronized
    fun registerOrUpdateRemoteParticipant(
        peerId: String,
        displayName: String,
        endpoint: String,
        wantsVideo: Boolean
    ): String {
        val lang = langProvider()
        val existing = participants[peerId]
        val maxLimit = getMaxParticipantsForCurrentMode()
        if (existing != null) {
            existing.displayName = displayName
            existing.endpoint = endpoint
            existing.isVideoEnabled = wantsVideo && isVideoRoomMode
            existing.lastSeenMs = System.currentTimeMillis()
            return when (lang) {
                "en" -> "Updated AES-256-GCM stream for $displayName (${participants.size}/$maxLimit participants)."
                "tl" -> "Na-update ang AES-256-GCM stream ni $displayName (${participants.size}/$maxLimit kasali)."
                "es" -> "Flujo AES-256-GCM actualizado para $displayName (${participants.size}/$maxLimit participantes)."
                else -> "${displayName} さんの暗号化ストリームを更新しました（現在 ${participants.size}/${maxLimit}名）。"
            }
        }

        if (participants.size >= maxLimit) {
            return when (lang) {
                "en" -> "Maximum participants ($maxLimit) reached; blocked $displayName."
                "tl" -> "Naabot na ang maximum na kasali ($maxLimit); hindi na makapasok si $displayName."
                "es" -> "Se alcanzó el máximo de participantes ($maxLimit); se bloqueó a $displayName."
                else -> "最大参加人数（${maxLimit}人）に達しているため、${displayName} さんの追加を制限しました。"
            }
        }

        val defaultExp = when (lang) {
            "en" -> "Warm smile (89% smile) looking at the camera"
            "tl" -> "Masayang ngiti (89% ngiti) habang nakatingin sa camera"
            "es" -> "Sonrisa cálida (89%) mirando a la cámara"
            else -> "穏やかな笑顔（笑顔度 89%）でカメラを見ています"
        }

        participants[peerId] = MeshParticipant(
            peerId = peerId,
            displayName = displayName,
            endpoint = endpoint,
            isVideoEnabled = wantsVideo && isVideoRoomMode,
            lastSeenMs = System.currentTimeMillis(),
            latestExpressionSummary = defaultExp
        )

        // 入った音（入室チャイム）を再生！
        KapamilyaPhoneSoundEngine.playParticipantJoinedSound()

        val status = when (lang) {
            "en" -> "🔐 AES-256-GCM Verified: $displayName joined the call! (${participants.size}/$maxLimit participants • SAS: $currentKeyFingerprint)"
            "tl" -> "🔐 AES-256-GCM Verified: Pumasok na si $displayName sa tawag! (${participants.size}/$maxLimit kasali • SAS: $currentKeyFingerprint)"
            "es" -> "🔐 AES-256-GCM Verificado: ¡$displayName se unió a la llamada! (${participants.size}/$maxLimit • SAS: $currentKeyFingerprint)"
            else -> "🔐 AES-256-GCM 認証完了：${displayName} さんが入室しました！（現在 ${participants.size}/${maxLimit}名・安全確認コード: $currentKeyFingerprint）"
        }
        onMeshEvent(status)
        return status
    }

    /**
     * 参加者（またはボット）の退室・切断テストおよび退室音（落ちた音）の再生
     */
    @Synchronized
    fun simulateOrHandleParticipantLeave(specificPeerId: String? = null): String {
        val lang = langProvider()
        val remotes = participants.values.filter { it.peerId != localPeerId }.sortedByDescending { it.peerId }
        val target = if (specificPeerId != null) {
            participants[specificPeerId]
        } else {
            remotes.firstOrNull()
        }

        // 落ちた音（退室チャイム）を必ず再生！
        KapamilyaPhoneSoundEngine.playParticipantLeftSound()

        if (target == null) {
            return when (lang) {
                "en" -> "Participant left sound played. Currently only you are in the room."
                "tl" -> "ipinatugtog ang tunog ng pag-alis. Ikaw na lang ang nasa room ngayon."
                "es" -> "Sonido de salida reproducido. Solo estás tú en la sala."
                else -> "退室サウンド（落ちた音）を再生しました。現在ルームにはあなたのみが参加しています。"
            }
        }

        participants.remove(target.peerId)
        val maxLimit = getMaxParticipantsForCurrentMode()
        val msg = when (lang) {
            "en" -> "🍂 ${target.displayName} left the call (${participants.size}/$maxLimit remaining)."
            "tl" -> "🍂 Umalis na si ${target.displayName} sa tawag (${participants.size}/$maxLimit ang natitira)."
            "es" -> "🍂 ${target.displayName} salió de la llamada (quedan ${participants.size}/$maxLimit)."
            else -> "🍂 ${target.displayName} さんが退室（切断）しました（残り ${participants.size}/${maxLimit}名）。"
        }
        onMeshEvent(msg)
        return msg
    }

    fun startMeshSignalingService() {
        if (isRunning) return
        isRunning = true

        ioExecutor.execute {
            try {
                val sock = DatagramSocket(MESH_UDP_PORT).apply {
                    broadcast = true
                }
                udpSocket = sock
                val buf = ByteArray(8192)
                while (isRunning) {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val wire = String(pkt.data, 0, pkt.length, StandardCharsets.UTF_8)
                    val plain = decryptAes256Gcm(wire) ?: continue
                    handleAuthenticatedMeshPacket(plain, pkt.address.hostAddress ?: "peer")
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stopMeshSignalingService() {
        isRunning = false
        try {
            broadcastEncryptedMeshPacket("MESH_LEAVE|peer=$localPeerId|name=$localDisplayName")
        } catch (_: Exception) {}
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null
    }

    fun broadcastEncryptedJoin() {
        broadcastEncryptedMeshPacket(
            "MESH_JOIN|peer=$localPeerId|name=$localDisplayName|video=${if (isVideoRoomMode) 1 else 0}"
        )
    }

    fun broadcastEncryptedExpression(expressionSummary: String) {
        participants[localPeerId]?.latestExpressionSummary = expressionSummary
        broadcastEncryptedMeshPacket(
            "MESH_EXPRESSION|peer=$localPeerId|name=$localDisplayName|exp=$expressionSummary"
        )
    }

    private fun broadcastEncryptedMeshPacket(plainPayload: String) {
        ioExecutor.execute {
            try {
                val encrypted = encryptAes256Gcm(plainPayload)
                val data = encrypted.toByteArray(StandardCharsets.UTF_8)
                DatagramSocket().use { sender ->
                    sender.broadcast = true
                    val pkt = DatagramPacket(
                        data,
                        data.size,
                        InetAddress.getByName("255.255.255.255"),
                        MESH_UDP_PORT
                    )
                    sender.send(pkt)
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleAuthenticatedMeshPacket(plain: String, senderIp: String) {
        if (plain.contains("peer=$localPeerId")) return

        var peerId = "PEER-$senderIp"
        var name = "Kapamilya ($senderIp)"
        var video = true
        var exp = ""

        for (token in plain.split("|")) {
            when {
                token.startsWith("peer=") -> peerId = token.removePrefix("peer=")
                token.startsWith("name=") -> name = token.removePrefix("name=")
                token.startsWith("video=") -> video = token.removePrefix("video=") == "1"
                token.startsWith("exp=") -> exp = token.removePrefix("exp=")
            }
        }

        when {
            plain.startsWith("MESH_JOIN|") -> {
                registerOrUpdateRemoteParticipant(peerId, name, senderIp, video)
            }
            plain.startsWith("MESH_LEAVE|") -> {
                simulateOrHandleParticipantLeave(peerId)
            }
            plain.startsWith("MESH_EXPRESSION|") -> {
                val p = participants[peerId]
                if (p != null && exp.isNotBlank()) {
                    p.latestExpressionSummary = exp
                    p.lastSeenMs = System.currentTimeMillis()
                    onPartnerExpressionReceived(p.displayName, exp)
                }
            }
        }
    }

    fun buildAllParticipantsAndExpressionsSummary(lang: String = langProvider()): String {
        val maxCap = getMaxParticipantsForCurrentMode()
        val list = getParticipantsList()
        val remotes = list.filter { it.peerId != localPeerId }

        return when (lang) {
            "en" -> {
                val modeLabel = if (isVideoRoomMode) "Video Call (Max 6)" else "Audio Call (Max 10)"
                val sb = StringBuilder("🔐 AES-256-GCM Encrypted (SAS: $currentKeyFingerprint) • $modeLabel Participants: ${list.size}/$maxCap. ")
                if (remotes.isEmpty()) {
                    sb.append("You ($localDisplayName) are waiting in the room. Up to ${maxCap - 1} more can join.")
                } else {
                    for ((idx, p) in remotes.withIndex()) {
                        sb.append("[#${idx + 2}] ${p.displayName}: ${p.latestExpressionSummary}. ")
                    }
                }
                sb.toString()
            }
            "tl" -> {
                val modeLabel = if (isVideoRoomMode) "Video Call (Max 6)" else "Audio Call (Max 10)"
                val sb = StringBuilder("🔐 AES-256-GCM Encrypted (SAS: $currentKeyFingerprint) • $modeLabel Kasali: ${list.size}/$maxCap. ")
                if (remotes.isEmpty()) {
                    sb.append("Naghihintay ka ($localDisplayName) sa room. Hanggang ${maxCap - 1} pa ang puwedeng sumali.")
                } else {
                    for ((idx, p) in remotes.withIndex()) {
                        sb.append("[#${idx + 2}] ${p.displayName}: ${p.latestExpressionSummary}. ")
                    }
                }
                sb.toString()
            }
            "es" -> {
                val modeLabel = if (isVideoRoomMode) "Videollamada (Máx 6)" else "Llamada de Voz (Máx 10)"
                val sb = StringBuilder("🔐 Cifrado AES-256-GCM (SAS: $currentKeyFingerprint) • $modeLabel Participantes: ${list.size}/$maxCap. ")
                if (remotes.isEmpty()) {
                    sb.append("Estás esperando en la sala. Pueden unirse hasta ${maxCap - 1} personas más.")
                } else {
                    for ((idx, p) in remotes.withIndex()) {
                        sb.append("[#${idx + 2}] ${p.displayName}: ${p.latestExpressionSummary}. ")
                    }
                }
                sb.toString()
            }
            else -> {
                val modeLabel = if (isVideoRoomMode) "ビデオ通話（最大6人）" else "音声通話（最大10人）"
                val sb = StringBuilder("🔐 AES-256-GCM 完全暗号化（安全確認コード: $currentKeyFingerprint）• $modeLabel 参加人数: ${list.size}/${maxCap}名。")
                if (remotes.isEmpty()) {
                    sb.append("現在あなた（$localDisplayName）がルームで待機中です。あと ${maxCap - 1}名まで参加できます。")
                } else {
                    for ((idx, p) in remotes.withIndex()) {
                        sb.append(" [${idx + 2}人目] ${p.displayName}: ${p.latestExpressionSummary}。")
                    }
                }
                sb.toString()
            }
        }
    }

    // =========================================================================
    // 4. Kapamilya Serena AI テストボット（1対1 / 6人ビデオ / 10人音声）
    //    入った音・自動音声応答・世界4言語完全対応
    // =========================================================================
    private var testBotTurnIndex = 0

    private fun getBotCatalog(lang: String): List<Triple<String, String, String>> = when (lang) {
        "en" -> listOf(
            Triple("BOT-01", "🤖 Serena AI (Manila Main Bot)", "Beaming with a warm smile (98% smile) and nodding gently at the camera"),
            Triple("BOT-02", "🌺 Maria (Cebu Test Bot)", "Smiling brightly (92% smile) and waving her hand"),
            Triple("BOT-03", "🌸 Sakura (Tokyo Test Bot)", "Smiling warmly (89% smile) and nodding in agreement"),
            Triple("BOT-04", "🎸 Juan (Davao Test Bot)", "Grinning happily (94% smile) and giving a thumbs up"),
            Triple("BOT-05", "🌟 Elena (Madrid Test Bot)", "Relaxed warm smile (87% smile) looking at the screen"),
            Triple("BOT-06", "🎵 Kenji (Osaka Audio Bot)", "Connected with clear, cheerful voice"),
            Triple("BOT-07", "🌴 Liza (Iloilo Audio Bot)", "Connected with warm, calm voice"),
            Triple("BOT-08", "🌊 Carlos (Quezon Audio Bot)", "Connected on crystal-clear audio stream"),
            Triple("BOT-09", "🍀 Aoi (Fukuoka Audio Bot)", "Connected with noise-free HD voice")
        )
        "tl" -> listOf(
            Triple("BOT-01", "🤖 Serena AI (Manila Main Bot)", "Nakangiti nang buong puso (98% ngiti) at marahang tumatango sa camera"),
            Triple("BOT-02", "🌺 Maria (Cebu Test Bot)", "Masayang nakangiti (92% ngiti) habang kumakaway"),
            Triple("BOT-03", "🌸 Sakura (Tokyo Test Bot)", "Mahinahong nakangiti (89% ngiti) at tumatango"),
            Triple("BOT-04", "🎸 Juan (Davao Test Bot)", "Masayang tumatawa (94% ngiti) at naka-thumbs up"),
            Triple("BOT-05", "🌟 Elena (Madrid Test Bot)", "Maaliwalas ang ngiti (87% ngiti) habang nakatingin sa screen"),
            Triple("BOT-06", "🎵 Kenji (Osaka Audio Bot)", "Handang sumagot nang may masiglang boses"),
            Triple("BOT-07", "🌴 Liza (Iloilo Audio Bot)", "Kasali nang may mahinahong boses"),
            Triple("BOT-08", "🌊 Carlos (Quezon Audio Bot)", "Nakakonekta sa malinaw na audio stream"),
            Triple("BOT-09", "🍀 Aoi (Fukuoka Audio Bot)", "Nakakonekta sa napakalinaw na HD audio")
        )
        "es" -> listOf(
            Triple("BOT-01", "🤖 Serena IA (Bot Principal Manila)", "Sonriendo cálidamente (98% sonrisa) y asintiendo frente a la cámara"),
            Triple("BOT-02", "🌺 Maria (Bot de Cebu)", "Sonriendo alegremente (92% sonrisa) y saludando con la mano"),
            Triple("BOT-03", "🌸 Sakura (Bot de Tokio)", "Sonrisa suave (89% sonrisa) asintiendo con atención"),
            Triple("BOT-04", "🎸 Juan (Bot de Davao)", "Riendo felizmente (94% sonrisa) con el pulgar arriba"),
            Triple("BOT-05", "🌟 Elena (Bot de Madrid)", "Sonrisa relajada (87% sonrisa) mirando la pantalla"),
            Triple("BOT-06", "🎵 Kenji (Bot de Voz Osaka)", "Conectado con voz clara y alegre"),
            Triple("BOT-07", "🌴 Liza (Bot de Voz Iloilo)", "Conectada con voz cálida y tranquila"),
            Triple("BOT-08", "🌊 Carlos (Bot de Voz Quezon)", "Conectado con flujo de audio nítido"),
            Triple("BOT-09", "🍀 Aoi (Bot de Voz Fukuoka)", "Conectada con audio HD sin ruido")
        )
        else -> listOf(
            Triple("BOT-01", "🤖 Serena AI (マニラ・メインボット)", "満面の笑顔（笑顔度 98%）でカメラの正面から優しくうなずいています"),
            Triple("BOT-02", "🌺 Maria (セブ・テストボット)", "明るい笑顔（笑顔度 92%）で手を振りながら話を聞いています"),
            Triple("BOT-03", "🌸 Sakura (東京・テストボット)", "穏やかな微笑み（笑顔度 89%）で相槌を打っています"),
            Triple("BOT-04", "🎸 Juan (ダバオ・テストボット)", "楽しそうに笑いながら（笑顔度 94%）親指を立てています"),
            Triple("BOT-05", "🌟 Elena (マドリード・テストボット)", "リラックスした笑顔（笑顔度 87%）で画面を見つめています"),
            Triple("BOT-06", "🎵 Kenji (大阪・音声ボット)", "元気な声でハキハキと応答待機中"),
            Triple("BOT-07", "🌴 Liza (イロイロ・音声ボット)", "温かく落ち着いた声で参加中"),
            Triple("BOT-08", "🌊 Carlos (ケソン・音声ボット)", "クリアな音声ストリームで接続中"),
            Triple("BOT-09", "🍀 Aoi (福岡・音声ボット)", "ノイズのない高音質ストリームで参加中")
        )
    }

    @Synchronized
    fun spawnTestBots(botCountTarget: Int, isVideo: Boolean, lang: String = langProvider()): String {
        val maxLimit = if (isVideo) MAX_VIDEO_PARTICIPANTS else MAX_AUDIO_PARTICIPANTS
        val desiredTotal = botCountTarget.coerceIn(2, maxLimit)

        participants.clear()
        participants[localPeerId] = MeshParticipant(
            peerId = localPeerId,
            displayName = selfLabel(lang),
            endpoint = "local",
            isVideoEnabled = isVideo,
            lastSeenMs = System.currentTimeMillis(),
            latestExpressionSummary = selfInitialExp(lang)
        )

        val catalog = getBotCatalog(lang)
        val neededBots = desiredTotal - 1
        for (i in 0 until neededBots.coerceAtMost(catalog.size)) {
            val (id, name, initialExp) = catalog[i]
            participants[id] = MeshParticipant(
                peerId = id,
                displayName = name,
                endpoint = "AES256GCM-Loopback-Bot",
                isVideoEnabled = isVideo,
                lastSeenMs = System.currentTimeMillis(),
                latestExpressionSummary = initialExp
            )
        }

        return when (lang) {
            "en" -> if (neededBots == 1) {
                "🤖 Kapamilya Serena AI Test Bot connected via AES-256-GCM (SAS: $currentKeyFingerprint)! Hi there! I can hear you clearly—just speak naturally and I will automatically reply!"
            } else {
                "👥 Kapamilya AI Group Test Bots (${participants.size}/$maxLimit participants) joined via AES-256-GCM (SAS: $currentKeyFingerprint)! Speak anytime and our group will automatically reply with voice and facial expressions!"
            }
            "tl" -> if (neededBots == 1) {
                "🤖 Nakakonekta na ang Kapamilya Serena AI Test Bot sa AES-256-GCM (SAS: $currentKeyFingerprint)! Kumusta, Kapamilya! Rinig na rinig kita—magsalita ka lang at kusang sasagot ako!"
            } else {
                "👥 Pumasok na ang Kapamilya AI Group Test Bots (${participants.size}/$maxLimit kasali) sa AES-256-GCM (SAS: $currentKeyFingerprint)! Magsalita ka lang at kusang sasagot ang buong grupo!"
            }
            "es" -> if (neededBots == 1) {
                "🤖 ¡Bot de Prueba Kapamilya Serena IA conectado con cifrado AES-256-GCM (SAS: $currentKeyFingerprint)! ¡Hola! Te escucho perfectamente, habla cuando quieras y te responderé automáticamente."
            } else {
                "👥 ¡Grupo de Bots IA Kapamilya (${participants.size}/$maxLimit participantes) conectados con AES-256-GCM (SAS: $currentKeyFingerprint)! Habla cuando quieras y todo el grupo responderá automáticamente."
            }
            else -> if (neededBots == 1) {
                "🤖 Kapamilya Serena AI テストボットが AES-256-GCM 暗号化回線（安全確認コード: $currentKeyFingerprint）で入室しました！もしもし、Shinji！そのまま声を出して話しかけてくれれば、ボタン操作なしでわたしが自動でお返事するよ！"
            } else {
                "👥 Kapamilya AI グループテストボット（合計 ${participants.size}/${maxLimit}名）が AES-256-GCM 暗号化メッシュ（安全確認コード: $currentKeyFingerprint）に入室しました！そのまま声で話しかけるとボットたちが自動で返事するよ！"
            }
        }
    }

    @Synchronized
    fun processUserSpeechWithTestBot(userUtterance: String, lang: String = langProvider()): String {
        val cleanInput = userUtterance.trim()
        val packetPlain = "BOT_VOICE_TURN|from=$localDisplayName|text=$cleanInput|ts=${System.currentTimeMillis()}"
        val encryptedWire = encryptAes256Gcm(packetPlain)
        val decryptedCheck = decryptAes256Gcm(encryptedWire)
            ?: return "AES-256-GCM Decryption Error"

        if (participants.size <= 1) {
            spawnTestBots(2, isVideoRoomMode, lang)
        }

        val remotes = participants.values.filter { it.peerId != localPeerId }.sortedBy { it.peerId }
        val mainBot = remotes.firstOrNull() ?: return ""
        testBotTurnIndex++
        val lower = cleanInput.lowercase()

        val (replySpeech, newMainExpression, groupExtraSpeech) = when (lang) {
            "en" -> buildBotTurnEn(cleanInput, lower, remotes.size, participants.size)
            "tl" -> buildBotTurnTl(cleanInput, lower, remotes.size, participants.size)
            "es" -> buildBotTurnEs(cleanInput, lower, remotes.size, participants.size)
            else -> buildBotTurnJa(cleanInput, lower, remotes, participants.size)
        }

        mainBot.latestExpressionSummary = newMainExpression
        mainBot.lastSeenMs = System.currentTimeMillis()
        onPartnerExpressionReceived(mainBot.displayName, newMainExpression)

        val prefix = when (lang) {
            "en" -> "🤖 Serena AI Bot"
            "tl" -> "🤖 Serena AI Bot"
            "es" -> "🤖 Bot Serena IA"
            else -> "🤖 セレナAIボット"
        }

        return if (groupExtraSpeech.isNotBlank() && decryptedCheck.isNotEmpty()) {
            "$prefix: \"$replySpeech\" $groupExtraSpeech"
        } else {
            "$prefix: \"$replySpeech\""
        }
    }

    private fun buildBotTurnJa(
        cleanInput: String,
        lower: String,
        remotes: List<MeshParticipant>,
        totalCount: Int
    ): Triple<String, String, String> {
        return when {
            cleanInput.isEmpty() -> {
                val preset = listOf(
                    Triple(
                        "もしもし、Shinji！セレナAIテストボットだよ！AES-256-GCM（安全確認コード: $currentKeyFingerprint）であなたの声がとてもクリアに届いているよ。何でもそのまま声で話しかけてね！",
                        "満面の笑顔（笑顔度 99%）で嬉しそうに大きく2回うなずいています",
                        if (remotes.size > 1) "他のメンバー（${remotes.size}名）も全員笑顔で手を振っているよ！" else ""
                    ),
                    Triple(
                        "カメラとマイクの自動応答テストもバッチリだよ！わたしが話し終わると自動でマイクが聞き取りモードになるから、ハンズフリーでそのまま会話できるよ！",
                        "安心させるような優しい微笑み（笑顔度 95%）でカメラの正面を見つめています",
                        if (remotes.size > 1) "マリアさんとさくらさんも「すごく自然な会話だね！」と拍手しているよ！" else ""
                    ),
                    Triple(
                        "Mabuhay, Shinji! フィリピンと日本、そして世界4言語（日本語・英語・タガログ語・スペイン語）に完全対応したよ！通話を切ったときには切断音と一緒に1秒単位の通話時間も読み上げるよ！",
                        "誇らしげな明るい笑顔（笑顔度 98%）で胸に手を当てて微笑んでいます",
                        if (remotes.size > 1) "グループ全員（${totalCount}名）が笑顔でうなずき合っています！" else ""
                    )
                )
                preset[(testBotTurnIndex - 1) % preset.size]
            }
            lower.contains("顔") || lower.contains("表情") || lower.contains("見え") || lower.contains("カメラ") -> Triple(
                "うん、Shinji！今わたしはカメラの正面で満面の笑顔（笑顔度 99%）でピースサインをしているよ！あなたの表情も明るくしっかり見えているよ！",
                "カメラに向かって最高の笑顔（笑顔度 99%）でピースサインをしています",
                if (remotes.size > 1) "ビデオ参加中のメンバー全員がカメラに向かって笑顔でポーズをとっています！" else ""
            )
            lower.contains("暗号") || lower.contains("セキュリティ") || lower.contains("安全") || lower.contains("aes") -> Triple(
                "すべての通信は PBKDF2-HMAC-SHA256 10万回反復と 96ビットIV・128ビット認証タグ付き AES-256-GCM で完全暗号化されているよ！安全確認コードは $currentKeyFingerprint だよ！",
                "頼もしい笑顔（笑顔度 94%）でしっかりうなずいています",
                ""
            )
            lower.contains("何人") || lower.contains("誰") || lower.contains("メンバー") -> {
                val names = remotes.joinToString("、") { it.displayName }
                Triple(
                    "今はあなたを含めて ${totalCount}人（上限 ${getMaxParticipantsForCurrentMode()}人）が通話中だよ！参加メンバーは $names だよ！",
                    "楽しそうな笑顔（笑顔度 96%）で周りのメンバーを紹介しています",
                    ""
                )
            }
            else -> Triple(
                "「$cleanInput」だね！バッチリはっきり聞こえたよ！AES-256-GCM 暗号化ストリーム（コード: $currentKeyFingerprint）で0ミリ秒で届いているよ。続けて何でも話しかけてね！",
                "嬉しそうに微笑みながら（笑顔度 97%）あなたの言葉に大きくうなずいています",
                if (remotes.size > 1) "${remotes.last().displayName} も笑顔で「うんうん、よく聞こえてるよ！」と相槌を打っています。" else ""
            )
        }
    }

    private fun buildBotTurnEn(
        cleanInput: String,
        lower: String,
        remoteCount: Int,
        totalCount: Int
    ): Triple<String, String, String> {
        return when {
            cleanInput.isEmpty() -> {
                val preset = listOf(
                    Triple(
                        "Hello! This is the Kapamilya Serena AI Test Bot! Our AES-256-GCM encrypted line (SAS: $currentKeyFingerprint) is crystal clear. Just speak out loud anytime and I will reply automatically!",
                        "Beaming with a warm 99% smile and nodding twice at the camera",
                        if (remoteCount > 1) "All $remoteCount group members are smiling and waving at you!" else ""
                    ),
                    Triple(
                        "Hands-free voice conversation is working great! As soon as I finish speaking, my microphone opens automatically to listen to your voice.",
                        "Smiling gently (95% smile) while looking straight into the camera",
                        if (remoteCount > 1) "Maria and Sakura are applauding and smiling!" else ""
                    ),
                    Triple(
                        "Mabuhay! Kapamilya Serena Phone now supports full localization in English, Japanese, Tagalog, and Spanish, with join/leave chimes and exact second-level call duration!",
                        "Bright proud smile (98% smile) with hand over heart",
                        if (remoteCount > 1) "All $totalCount participants are nodding happily together!" else ""
                    )
                )
                preset[(testBotTurnIndex - 1) % preset.size]
            }
            lower.contains("face") || lower.contains("smile") || lower.contains("expression") || lower.contains("camera") -> Triple(
                "I'm smiling brightly right in the center of the camera (99% smile) and giving you a peace sign! Gemma 4 and EmbeddingGemma 2 can describe everyone's facial expressions anytime!",
                "Smiling brightly (99% smile) and making a peace sign toward the camera",
                if (remoteCount > 1) "Everyone in the video group is smiling and posing for the camera!" else ""
            )
            lower.contains("encrypt") || lower.contains("security") || lower.contains("aes") -> Triple(
                "Everything is protected with AES-256-GCM using PBKDF2-HMAC-SHA256 (100,000 iterations), a 96-bit random IV, and a 128-bit authentication tag. Our SAS code is $currentKeyFingerprint!",
                "Confident, reassuring warm smile (94% smile)",
                ""
            )
            else -> Triple(
                "I heard you say \"$cleanInput\" loud and clear over our AES-256-GCM encrypted connection (SAS: $currentKeyFingerprint)! Go ahead and keep talking anytime!",
                "Smiling happily (97% smile) and nodding attentively to your words",
                if (remoteCount > 1) "The rest of the group ($totalCount participants) is smiling and nodding along!" else ""
            )
        }
    }

    private fun buildBotTurnTl(
        cleanInput: String,
        lower: String,
        remoteCount: Int,
        totalCount: Int
    ): Triple<String, String, String> {
        return when {
            cleanInput.isEmpty() -> {
                val preset = listOf(
                    Triple(
                        "Kumusta, aking Kapamilya! Ito ang Serena AI Test Bot! Napakalinaw ng ating AES-256-GCM encrypted line (SAS: $currentKeyFingerprint). Magsalita ka lang kahit kailan at kusang sasagot ako!",
                        "Nakangiti nang buong puso (99% ngiti) at tumatango nang dalawang beses sa camera",
                        if (remoteCount > 1) "Lahat ng $remoteCount kasama natin sa grupo ay nakangiti at kumakaway sa iyo!" else ""
                    ),
                    Triple(
                        "Gumagana nang perpekto ang ating automatic voice reply! Pagkatapos kong magsalita, awtomatikong nakikinig agad ako sa boses mo.",
                        "Maaliwalas at malambing na ngiti (95% ngiti) habang nakatingin sa camera",
                        if (remoteCount > 1) "Pumapalakpak at nakangiti sina Maria at Sakura!" else ""
                    ),
                    Triple(
                        "Mabuhay! Sinusuportahan na ng Kapamilya Serena Phone ang Tagalog, English, Japanese, at Español kasama ang tunog sa pagpasok, pag-alis, at eksaktong tagal ng tawag!",
                        "Masayang ngiti (98% ngiti) habang nakahawak sa dibdib",
                        if (remoteCount > 1) "Masayang tumatango ang lahat ng $totalCount kasali sa grupo!" else ""
                    )
                )
                preset[(testBotTurnIndex - 1) % preset.size]
            }
            lower.contains("mukha") || lower.contains("ngiti") || lower.contains("camera") || lower.contains("ekspresyon") -> Triple(
                "Nakangiti ako nang napakasaya sa harap ng camera (99% ngiti) at naka-peace sign! Maaari mong suriin ang ekspresyon naming lahat gamit ang Gemma 4 at EmbeddingGemma 2!",
                "Napakasayang ngiti (99% ngiti) at naka-peace sign sa harap ng camera",
                if (remoteCount > 1) "Nakangiti ang lahat ng kasama natin sa video call!" else ""
            )
            else -> Triple(
                "Rinig na rinig ko ang sinabi mong \"$cleanInput\" sa ating ligtas na AES-256-GCM connection (SAS: $currentKeyFingerprint)! Sige lang, kuwentuhan pa tayo!",
                "Masayang nakangiti (97% ngiti) at tumatango sa iyong sinabi",
                if (remoteCount > 1) "Nakangiti at sumasang-ayon din ang buong grupo ($totalCount kasali)!" else ""
            )
        }
    }

    private fun buildBotTurnEs(
        cleanInput: String,
        lower: String,
        remoteCount: Int,
        totalCount: Int
    ): Triple<String, String, String> {
        return when {
            cleanInput.isEmpty() -> {
                val preset = listOf(
                    Triple(
                        "¡Hola, mi Kapamilya! ¡Soy el Bot de Prueba Serena IA! Nuestra línea cifrada con AES-256-GCM (SAS: $currentKeyFingerprint) se escucha con total claridad. ¡Habla cuando quieras y te responderé automáticamente!",
                        "Sonriendo ampliamente (99% sonrisa) y asintiendo dos veces hacia la cámara",
                        if (remoteCount > 1) "¡Los $remoteCount miembros del grupo te están sonriendo y saludando!" else ""
                    ),
                    Triple(
                        "¡La respuesta automática por voz funciona de maravilla! En cuanto termino de hablar, el micrófono te escucha automáticamente sin tocar ningún botón.",
                        "Sonrisa suave y cálida (95% sonrisa) mirando directamente a la cámara",
                        if (remoteCount > 1) "¡Maria y Sakura están aplaudiendo con una gran sonrisa!" else ""
                    ),
                    Triple(
                        "¡Mabuhay! Kapamilya Serena Phone ahora está totalmente localizado en español, inglés, japonés y tagalo, con sonidos de entrada, salida y duración exacta de la llamada.",
                        "Sonrisa orgullosa y brillante (98% sonrisa)",
                        if (remoteCount > 1) "¡Los $totalCount participantes asienten felizmente!" else ""
                    )
                )
                preset[(testBotTurnIndex - 1) % preset.size]
            }
            lower.contains("cara") || lower.contains("rostro") || lower.contains("sonrisa") || lower.contains("cámara") -> Triple(
                "¡Estoy sonriendo frente a la cámara (99% sonrisa) haciendo la señal de la paz! Gemma 4 y EmbeddingGemma 2 pueden describir nuestras expresiones en tiempo real.",
                "Sonrisa radiante (99% sonrisa) haciendo la señal de la paz a la cámara",
                if (remoteCount > 1) "¡Todos los participantes de la videollamada están sonriendo a la cámara!" else ""
            )
            else -> Triple(
                "¡Escuché claramente \"$cleanInput\" a través de nuestra conexión cifrada AES-256-GCM (SAS: $currentKeyFingerprint)! ¡Sigue hablando cuando quieras!",
                "Sonriendo alegremente (97% sonrisa) y asintiendo a tus palabras",
                if (remoteCount > 1) "¡Todo el grupo ($totalCount participantes) sonríe y asiente contigo!" else ""
            )
        }
    }
}
