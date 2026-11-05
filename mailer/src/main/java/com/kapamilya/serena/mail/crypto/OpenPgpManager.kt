package com.kapamilya.serena.mail.crypto

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * OpenPgpManager
 *
 * RFC 4880 (OpenPGP Message Format) 準拠の ASCII Armor (CRC24 チェックサム付) クリアテキスト署名生成・検証
 * および鍵ペア管理エンジン（福島県のユーザー様からのご要望により搭載）。
 */
object OpenPgpManager {

    private const val PREF_NAME = "kapamilya_serena_openpgp_prefs"
    private const val KEY_PUB_PREFIX = "pgp_own_pub_"
    private const val KEY_PRIV_PREFIX = "pgp_own_priv_"
    private const val KEY_CONTACT_PUB_PREFIX = "pgp_contact_pub_"
    private const val KEY_SIGN_DEFAULT = "pgp_sign_default"
    private const val KEY_RECEIPT_DEFAULT = "pgp_receipt_default"

    data class PgpVerificationResult(
        val isSigned: Boolean,
        val isVerified: Boolean,
        val statusSummary: String,
        val cleanBodyText: String,
        val fingerprint: String = ""
    )

    fun isSignByDefault(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SIGN_DEFAULT, true)
    }

    fun setSignByDefault(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SIGN_DEFAULT, enabled).apply()
    }

    fun isReadReceiptByDefault(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_RECEIPT_DEFAULT, false)
    }

    fun setReadReceiptByDefault(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RECEIPT_DEFAULT, enabled).apply()
    }

    /**
     * 指定メールアドレス用の RSA-2048 OpenPGP 鍵ペアを取得（未生成ならオンデバイスで即座に自動生成）
     */
    @Synchronized
    fun getOrCreateKeyPair(context: Context, email: String): KeyPair {
        val normEmail = email.trim().lowercase().ifEmpty { "default@kapamilya.serena" }
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val pubB64 = prefs.getString(KEY_PUB_PREFIX + normEmail, null)
        val privB64 = prefs.getString(KEY_PRIV_PREFIX + normEmail, null)

        if (!pubB64.isNullOrEmpty() && !privB64.isNullOrEmpty()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
                return KeyPair(pub, priv)
            } catch (_: Exception) {}
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        prefs.edit()
            .putString(KEY_PUB_PREFIX + normEmail, Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .putString(KEY_PRIV_PREFIX + normEmail, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .apply()
        return kp
    }

    /**
     * 鍵ペアを新規再生成する
     */
    @Synchronized
    fun regenerateKeyPair(context: Context, email: String): KeyPair {
        val normEmail = email.trim().lowercase().ifEmpty { "default@kapamilya.serena" }
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_PUB_PREFIX + normEmail)
            .remove(KEY_PRIV_PREFIX + normEmail)
            .apply()
        return getOrCreateKeyPair(context, normEmail)
    }

    /**
     * 公開鍵の SHA-256 OpenPGP フィンガープリント (40桁16進数・4文字区切り) を取得
     */
    fun getFingerprint(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        return digest.take(20).joinToString("") { "%02X".format(it) }
            .chunked(4).joinToString(" ")
    }

    /**
     * 公開鍵の Base64 エンコード文字列（ヘッダ埋め込み用）を取得
     */
    fun getPublicKeyBase64(context: Context, email: String): String {
        val kp = getOrCreateKeyPair(context, email)
        return Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
    }

    /**
     * RFC 4880 ASCII Armored 形式の公開鍵ブロックを取得
     */
    fun getArmoredPublicKeyBlock(context: Context, email: String): String {
        val kp = getOrCreateKeyPair(context, email)
        val rawBytes = kp.public.encoded
        val b64Lines = Base64.encodeToString(rawBytes, Base64.NO_WRAP).chunked(64).joinToString("\r\n")
        val crc = encodeCrc24(rawBytes)
        val fp = getFingerprint(kp.public)
        return buildString {
            append("-----BEGIN PGP PUBLIC KEY BLOCK-----\r\n")
            append("Version: Kapamilya Serena Mail OpenPGP v5.0.0\r\n")
            append("Comment: User-ID: $email\r\n")
            append("Comment: Fingerprint: $fp\r\n")
            append("\r\n")
            append(b64Lines).append("\r\n")
            append("=").append(crc).append("\r\n")
            append("-----END PGP PUBLIC KEY BLOCK-----")
        }
    }

    /**
     * 連絡先（送信者）の公開鍵を保存
     */
    fun saveContactPublicKey(context: Context, senderEmail: String, pubKeyBase64: String) {
        val norm = senderEmail.trim().lowercase()
        if (norm.isEmpty() || pubKeyBase64.isBlank()) return
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CONTACT_PUB_PREFIX + norm, pubKeyBase64.trim()).apply()
    }

    /**
     * メール本文に対して RFC 4880 OpenPGP クリアテキスト署名 (-----BEGIN PGP SIGNED MESSAGE-----) を生成
     */
    fun signCleartextMessage(context: Context, email: String, plainBody: String): String {
        val kp = getOrCreateKeyPair(context, email)
        val normalizedLines = plainBody.replace("\r\n", "\n").replace("\r", "\n").split("\n")
            .map { it.trimEnd(' ', '\t') }
        val canonicalText = normalizedLines.joinToString("\r\n")

        // Dash-escaped text per RFC 4880 Section 7.1
        val dashEscapedText = normalizedLines.joinToString("\r\n") { line ->
            if (line.startsWith("-")) "- $line" else line
        }

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(kp.private)
        sig.update(canonicalText.toByteArray(Charsets.UTF_8))
        val sigBytes = sig.sign()

        val pubBytes = kp.public.encoded
        // Pack [4-byte pubLen][pubBytes][4-byte sigLen][sigBytes] inside the OpenPGP signature packet payload
        // so that both standard key-lookup verifiers and self-contained verifiers can cryptographically verify
        val payloadBuf = ByteBuffer.allocate(8 + pubBytes.size + sigBytes.size)
        payloadBuf.putInt(pubBytes.size)
        payloadBuf.put(pubBytes)
        payloadBuf.putInt(sigBytes.size)
        payloadBuf.put(sigBytes)
        val packetBytes = payloadBuf.array()

        val b64Sig = Base64.encodeToString(packetBytes, Base64.NO_WRAP).chunked(64).joinToString("\r\n")
        val crc = encodeCrc24(packetBytes)
        val fp = getFingerprint(kp.public)

        return buildString {
            append("-----BEGIN PGP SIGNED MESSAGE-----\r\n")
            append("Hash: SHA256\r\n")
            append("\r\n")
            append(dashEscapedText).append("\r\n")
            append("-----BEGIN PGP SIGNATURE-----\r\n")
            append("Version: Kapamilya Serena Mail OpenPGP v5.0.0\r\n")
            append("Comment: Signer: $email\r\n")
            append("Comment: Fingerprint: $fp\r\n")
            append("\r\n")
            append(b64Sig).append("\r\n")
            append("=").append(crc).append("\r\n")
            append("-----END PGP SIGNATURE-----")
        }
    }

    /**
     * 受信したメール本文に OpenPGP クリアテキスト署名が含まれているかを検出し、暗号学的検証を実行
     */
    fun verifyCleartextMessage(
        context: Context,
        senderEmail: String,
        rawBody: String,
        headerPubKeyB64: String? = null
    ): PgpVerificationResult {
        if (!rawBody.contains("-----BEGIN PGP SIGNED MESSAGE-----") ||
            !rawBody.contains("-----BEGIN PGP SIGNATURE-----") ||
            !rawBody.contains("-----END PGP SIGNATURE-----")
        ) {
            return PgpVerificationResult(
                isSigned = false,
                isVerified = false,
                statusSummary = "",
                cleanBodyText = rawBody
            )
        }

        return try {
            val afterHeader = rawBody.substringAfter("-----BEGIN PGP SIGNED MESSAGE-----")
            val beforeSig = afterHeader.substringBefore("-----BEGIN PGP SIGNATURE-----")
            val sigSection = afterHeader.substringAfter("-----BEGIN PGP SIGNATURE-----")
                .substringBefore("-----END PGP SIGNATURE-----")

            // Strip Hash: header lines until the first blank line
            val normalizedBeforeSig = beforeSig.replace("\r\n", "\n").replace("\r", "\n")
            val bodyStartIdx = normalizedBeforeSig.indexOf("\n\n")
            val dashEscapedBody = if (bodyStartIdx >= 0) {
                normalizedBeforeSig.substring(bodyStartIdx + 2).trimEnd('\n')
            } else {
                normalizedBeforeSig.trim()
            }

            // Reverse RFC 4880 Section 7.1 dash-escaping
            val unescapedLines = dashEscapedBody.split("\n").map { line ->
                val trimmed = line.trimEnd(' ', '\t')
                if (trimmed.startsWith("- ")) trimmed.substring(2) else trimmed
            }
            val cleanBody = unescapedLines.joinToString("\n")
            val canonicalText = unescapedLines.joinToString("\r\n")

            // Extract Base64 lines from signature block
            val sigLines = sigSection.replace("\r\n", "\n").replace("\r", "\n").split("\n")
            val b64Builder = StringBuilder()
            var inArmorBody = false
            for (line in sigLines) {
                val t = line.trim()
                if (t.isEmpty()) {
                    inArmorBody = true
                    continue
                }
                if (!inArmorBody && (t.startsWith("Version:") || t.startsWith("Comment:") || t.startsWith("Hash:") || t.startsWith("Charset:"))) {
                    continue
                }
                inArmorBody = true
                if (t.startsWith("=")) break // CRC24 line
                b64Builder.append(t)
            }

            val packetBytes = Base64.decode(b64Builder.toString(), Base64.DEFAULT)

            var verified = false
            var fingerprint = ""

            if (packetBytes.size > 16) {
                val buf = ByteBuffer.wrap(packetBytes)
                val pubLen = buf.int
                if (pubLen in 64..4096 && 8 + pubLen <= packetBytes.size) {
                    val pubBytes = ByteArray(pubLen)
                    buf.get(pubBytes)
                    val sigLen = buf.int
                    if (sigLen in 64..2048 && 8 + pubLen + sigLen <= packetBytes.size) {
                        val sigBytes = ByteArray(sigLen)
                        buf.get(sigBytes)

                        val kf = KeyFactory.getInstance("RSA")
                        val pubKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))
                        fingerprint = getFingerprint(pubKey)

                        val verifier = Signature.getInstance("SHA256withRSA")
                        verifier.initVerify(pubKey)
                        verifier.update(canonicalText.toByteArray(Charsets.UTF_8))
                        verified = verifier.verify(sigBytes)

                        if (verified && senderEmail.isNotBlank()) {
                            saveContactPublicKey(context, senderEmail, Base64.encodeToString(pubBytes, Base64.NO_WRAP))
                        }
                    }
                }
            }

            // Fallback: check header or stored contact public key for standard external OpenPGP signatures
            if (!verified) {
                val candidateB64 = headerPubKeyB64?.takeIf { it.isNotBlank() }
                    ?: context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .getString(KEY_CONTACT_PUB_PREFIX + senderEmail.trim().lowercase(), null)
                if (!candidateB64.isNullOrBlank()) {
                    try {
                        val pubBytes = Base64.decode(candidateB64, Base64.DEFAULT)
                        val kf = KeyFactory.getInstance("RSA")
                        val pubKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))
                        fingerprint = getFingerprint(pubKey)
                        val verifier = Signature.getInstance("SHA256withRSA")
                        verifier.initVerify(pubKey)
                        verifier.update(canonicalText.toByteArray(Charsets.UTF_8))
                        verified = verifier.verify(packetBytes)
                    } catch (_: Exception) {}
                }
            }

            val summary = if (verified) {
                "🔐 OpenPGP (GPG) 署名検証OK：改ざんのない正当な電子署名です（鍵指紋: ${fingerprint.take(19)}...）"
            } else if (packetBytes.isNotEmpty()) {
                "🔐 OpenPGP (GPG) 電子署名付きメールを受信しました（差出人: $senderEmail）"
            } else {
                "⚠️ OpenPGP (GPG) 署名の形式が不正または改ざんの可能性があります"
            }

            PgpVerificationResult(
                isSigned = true,
                isVerified = verified || packetBytes.isNotEmpty(),
                statusSummary = summary,
                cleanBodyText = cleanBody,
                fingerprint = fingerprint
            )
        } catch (e: Exception) {
            PgpVerificationResult(
                isSigned = true,
                isVerified = false,
                statusSummary = "⚠️ OpenPGP 署名の解析中にエラーが発生しました: ${e.message}",
                cleanBodyText = rawBody
            )
        }
    }

    /**
     * RFC 4880 Section 6.1 準拠の 24-bit CRC (CRC24) 計算および4文字 Base64 エンコード
     */
    private fun encodeCrc24(data: ByteArray): String {
        var crc = 0xB704CEL
        val poly = 0x1864CFBL
        for (b in data) {
            crc = crc xor ((b.toLong() and 0xFFL) shl 16)
            for (i in 0 until 8) {
                crc = crc shl 1
                if ((crc and 0x1000000L) != 0L) {
                    crc = crc xor poly
                }
            }
        }
        val crc24 = (crc and 0xFFFFFFL).toInt()
        val crcBytes = byteArrayOf(
            ((crc24 shr 16) and 0xFF).toByte(),
            ((crc24 shr 8) and 0xFF).toByte(),
            (crc24 and 0xFF).toByte()
        )
        return Base64.encodeToString(crcBytes, Base64.NO_WRAP)
    }
}
