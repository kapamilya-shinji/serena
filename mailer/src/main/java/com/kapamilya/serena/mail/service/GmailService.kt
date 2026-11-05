package com.kapamilya.serena.mail.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kapamilya.serena.mail.crypto.OpenPgpManager
import com.kapamilya.serena.mail.data.MailAttachment
import com.kapamilya.serena.mail.data.MailFolderType
import com.kapamilya.serena.mail.data.MailMessage
import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.DataSource
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Part
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility
import javax.mail.search.FlagTerm

/**
 * GmailService
 *
 * IETF RFC 3501 (IMAP4rev1) ＆ RFC 6154 (IMAP LIST Extension for Special-Use Mailboxes) 準拠の
 * 100%オープン標準・多言語対応メール送受信＆7大フォルダ管理エンジン。
 */
class GmailService {

    companion object {
        private const val TAG = "GmailService"
        private const val IMAP_HOST = "imap.gmail.com"
        private const val IMAP_PORT = "993"
        private const val SMTP_HOST = "smtp.gmail.com"
        private const val SMTP_PORT = "465"
    }

    private fun buildImapProperties(isOAuth: Boolean, timeoutMs: String = "15000"): Properties {
        return Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", IMAP_HOST)
            put("mail.imaps.port", IMAP_PORT)
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.checkserveridentity", "true")
            put("mail.imaps.timeout", timeoutMs)
            put("mail.imaps.connectiontimeout", timeoutMs)
            put("mail.imaps.writetimeout", timeoutMs)
            if (isOAuth) {
                put("mail.imaps.sasl.enable", "true")
                put("mail.imaps.sasl.mechanisms", "XOAUTH2")
                put("mail.imaps.auth.login.disable", "true")
                put("mail.imaps.auth.plain.disable", "true")
            }
        }
    }

    private fun decodeMimeHeaderSafely(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            MimeUtility.decodeText(raw)
        } catch (_: Exception) {
            raw
        }
    }

    /**
     * RFC 6154 SPECIAL-USE 属性 (\Inbox, \Flagged, \Sent, \Drafts, \All, \Junk, \Trash)
     * および多言語フォルダ名フォールバックによる標準フォルダ自動解決
     */
    private fun resolveSpecialUseFolder(store: Store, folderType: MailFolderType): Folder? {
        if (folderType == MailFolderType.INBOX) {
            return try { store.getFolder("INBOX") } catch (_: Exception) { null }
        }

        // 1. RFC 6154 SPECIAL-USE 属性を全フォルダから検索
        try {
            val allFolders = store.defaultFolder.list("*")
            for (folder in allFolders) {
                val imapFolder = folder as? IMAPFolder ?: continue
                val attrs = try { imapFolder.attributes } catch (_: Exception) { null } ?: continue
                for (targetAttr in folderType.specialUseAttributes) {
                    if (attrs.any { it.equals(targetAttr, ignoreCase = true) }) {
                        return folder
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "RFC 6154 SPECIAL-USE list scan fallback for $folderType: ${e.message}")
        }

        // 2. フォールバックフォルダ名による検索
        for (name in folderType.fallbackNames) {
            try {
                val candidate = store.getFolder(name)
                if (candidate.exists()) {
                    return candidate
                }
            } catch (_: Exception) {}
        }

        return null
    }

    /**
     * アカウント接続テスト (アプリパスワード / OAuth2 トークン両対応)
     */
    suspend fun testConnection(
        email: String,
        tokenOrPassword: String,
        isOAuth: Boolean = false
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        var store: Store? = null
        try {
            val props = buildImapProperties(isOAuth = isOAuth, timeoutMs = "10000")
            val session = Session.getInstance(props)
            store = session.getStore("imaps")
            store.connect(IMAP_HOST, email, tokenOrPassword)
            Result.success(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            try { store?.close() } catch (_: Exception) {}
        }
    }

    /**
     * 受信トレイから最新メッセージを取得 (後方互換ラッパー)
     */
    suspend fun fetchInbox(
        email: String,
        tokenOrPassword: String,
        isOAuth: Boolean = false,
        maxCount: Int = 30
    ): Result<List<MailMessage>> = fetchFolder(
        email = email,
        tokenOrPassword = tokenOrPassword,
        folderType = MailFolderType.INBOX,
        isOAuth = isOAuth,
        maxCount = maxCount
    )

    /**
     * 指定した標準フォルダ (INBOX / STARRED / SENT / DRAFTS / ALL_MAIL / SPAM / TRASH) からメッセージを取得
     */
    suspend fun fetchFolder(
        email: String,
        tokenOrPassword: String,
        folderType: MailFolderType = MailFolderType.INBOX,
        isOAuth: Boolean = false,
        maxCount: Int = 30
    ): Result<List<MailMessage>> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var targetFolder: Folder? = null
        try {
            val props = buildImapProperties(isOAuth = isOAuth, timeoutMs = "15000")
            val session = Session.getInstance(props)
            store = session.getStore("imaps")
            store.connect(IMAP_HOST, email, tokenOrPassword)

            var useFlaggedSearchFallback = false
            targetFolder = resolveSpecialUseFolder(store, folderType)
            if (targetFolder == null && folderType == MailFolderType.STARRED) {
                targetFolder = store.getFolder("INBOX")
                useFlaggedSearchFallback = true
            }
            if (targetFolder == null || !targetFolder.exists()) {
                return@withContext Result.success(emptyList())
            }

            targetFolder.open(Folder.READ_ONLY)
            val imapFolder = targetFolder as? IMAPFolder

            val rawMessages: Array<Message> = if (useFlaggedSearchFallback) {
                val flagged = targetFolder.search(FlagTerm(Flags(Flags.Flag.FLAGGED), true))
                if (flagged.size > maxCount) {
                    flagged.copyOfRange(flagged.size - maxCount, flagged.size)
                } else {
                    flagged
                }
            } else {
                val total = targetFolder.messageCount
                if (total <= 0) {
                    return@withContext Result.success(emptyList())
                }
                val startIndex = (total - maxCount + 1).coerceAtLeast(1)
                targetFolder.getMessages(startIndex, total)
            }

            val resultList = mutableListOf<MailMessage>()
            for (i in rawMessages.indices.reversed()) {
                ensureActive()
                val msg = rawMessages[i]
                try {
                    val uid = try {
                        if (imapFolder != null) imapFolder.getUID(msg) else msg.messageNumber.toLong()
                    } catch (_: Exception) {
                        msg.messageNumber.toLong()
                    }

                    var senderStr = "Unknown"
                    var senderAddress = ""
                    try {
                        val fromArray = msg.from
                        if (!fromArray.isNullOrEmpty()) {
                            val addr = fromArray[0] as? InternetAddress
                            senderStr = decodeMimeHeaderSafely(addr?.personal)
                                .ifBlank { addr?.address ?: decodeMimeHeaderSafely(fromArray[0].toString()) }
                            senderAddress = addr?.address ?: ""
                        }
                    } catch (_: Exception) {
                        val rawFrom = msg.getHeader("From")?.firstOrNull()
                        if (!rawFrom.isNullOrBlank()) {
                            val decodedFrom = decodeMimeHeaderSafely(rawFrom)
                            senderStr = decodedFrom
                            senderAddress = decodedFrom.substringAfter("<", decodedFrom).substringBefore(">").trim()
                        }
                    }

                    val toList = try {
                        msg.getRecipients(Message.RecipientType.TO)?.map { decodeMimeHeaderSafely(it.toString()) } ?: emptyList()
                    } catch (_: Exception) {
                        msg.getHeader("To")?.map { decodeMimeHeaderSafely(it) } ?: emptyList()
                    }

                    val subject = try {
                        msg.subject?.let { decodeMimeHeaderSafely(it) }
                            ?: msg.getHeader("Subject")?.firstOrNull()?.let { decodeMimeHeaderSafely(it) }
                            ?: "(No Subject)"
                    } catch (_: Exception) {
                        msg.getHeader("Subject")?.firstOrNull()?.let { decodeMimeHeaderSafely(it) } ?: "(No Subject)"
                    }

                    val body = extractBodyText(msg)
                    val isUnread = try { !msg.isSet(Flags.Flag.SEEN) } catch (_: Exception) { false }
                    val isStarred = try { msg.isSet(Flags.Flag.FLAGGED) || folderType == MailFolderType.STARRED } catch (_: Exception) { folderType == MailFolderType.STARRED }
                    val date = try { msg.sentDate ?: msg.receivedDate } catch (_: Exception) { null }

                    val receiptHeaderRaw = try {
                        msg.getHeader("Disposition-Notification-To")?.firstOrNull()
                            ?: msg.getHeader("Return-Receipt-To")?.firstOrNull()
                            ?: msg.getHeader("X-Confirm-Reading-To")?.firstOrNull()
                            ?: ""
                    } catch (_: Exception) { "" }
                    val receiptRequestedTo = if (receiptHeaderRaw.isNotBlank()) {
                        decodeMimeHeaderSafely(receiptHeaderRaw).substringAfter("<", decodeMimeHeaderSafely(receiptHeaderRaw)).substringBefore(">").trim()
                    } else ""

                    val contentTypeLower = try { (msg.contentType ?: "").lowercase() } catch (_: Exception) { "" }
                    val isReadReceiptMdn = contentTypeLower.contains("disposition-notification") ||
                        subject.startsWith("開封済み:") || subject.startsWith("Read:") || subject.startsWith("[開封確認通知]")

                    val pgpPubKeyHdr = try {
                        msg.getHeader("X-Kapamilya-OpenPGP-PublicKey")?.firstOrNull()?.trim() ?: ""
                    } catch (_: Exception) { "" }

                    val rfcMsgIdHdr = try {
                        msg.getHeader("Message-ID")?.firstOrNull()?.trim() ?: ""
                    } catch (_: Exception) { "" }

                    resultList.add(
                        MailMessage(
                            messageId = msg.messageNumber.toString(),
                            sender = senderStr,
                            senderAddress = senderAddress,
                            recipients = toList,
                            subject = subject,
                            body = body,
                            receivedDate = date,
                            isUnread = isUnread,
                            uid = uid,
                            isStarred = isStarred,
                            folderType = folderType,
                            readReceiptRequestedTo = receiptRequestedTo,
                            isReadReceiptMdn = isReadReceiptMdn,
                            hasOpenPgpSignature = body.contains("-----BEGIN PGP SIGNED MESSAGE-----") || contentTypeLower.contains("pgp-signature"),
                            openPgpPubKeyHeader = pgpPubKeyHdr,
                            rfcMessageIdHeader = rfcMsgIdHdr
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing message $i in $folderType: ${e.message}")
                }
            }

            Result.success(resultList)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch folder $folderType: ${e.message}", e)
            Result.failure(e)
        } finally {
            try { if (targetFolder?.isOpen == true) targetFolder.close(false) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    /**
     * メールの既読 / 未読フラグ（SEEN）を更新
     */
    suspend fun markAsRead(
        email: String,
        tokenOrPassword: String,
        uid: Long,
        isOAuth: Boolean = false,
        folderType: MailFolderType = MailFolderType.INBOX,
        isRead: Boolean = true
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null
        try {
            val props = buildImapProperties(isOAuth = isOAuth, timeoutMs = "10000")
            val session = Session.getInstance(props)
            store = session.getStore("imaps")
            store.connect(IMAP_HOST, email, tokenOrPassword)

            folder = resolveSpecialUseFolder(store, folderType) ?: store.getFolder("INBOX")
            folder.open(Folder.READ_WRITE)

            val msg = findMessageByUidOrNumber(folder, uid)
            if (msg != null) {
                msg.setFlag(Flags.Flag.SEEN, isRead)
                Log.i(TAG, "Updated SEEN=$isRead on message (UID=$uid) in ${folder.fullName}")
            }
            Result.success(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update SEEN=$isRead (UID=$uid): ${e.message}")
            Result.failure(e)
        } finally {
            try { if (folder?.isOpen == true) folder.close(true) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    /**
     * メールのスター（FLAGGED）状態を更新
     */
    suspend fun setStarred(
        email: String,
        tokenOrPassword: String,
        uid: Long,
        starred: Boolean,
        isOAuth: Boolean = false,
        folderType: MailFolderType = MailFolderType.INBOX
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null
        try {
            val props = buildImapProperties(isOAuth = isOAuth, timeoutMs = "10000")
            val session = Session.getInstance(props)
            store = session.getStore("imaps")
            store.connect(IMAP_HOST, email, tokenOrPassword)

            folder = resolveSpecialUseFolder(store, folderType) ?: store.getFolder("INBOX")
            folder.open(Folder.READ_WRITE)

            val msg = findMessageByUidOrNumber(folder, uid)
            if (msg != null) {
                msg.setFlag(Flags.Flag.FLAGGED, starred)
            }
            Result.success(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update FLAGGED=$starred (UID=$uid): ${e.message}")
            Result.failure(e)
        } finally {
            try { if (folder?.isOpen == true) folder.close(true) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    /**
     * メールをアーカイブ（受信トレイから外し「すべてのメール」に残す標準IMAP動作）
     */
    suspend fun archiveMessage(
        email: String,
        tokenOrPassword: String,
        uid: Long,
        isOAuth: Boolean = false,
        folderType: MailFolderType = MailFolderType.INBOX
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var sourceFolder: Folder? = null
        try {
            val props = buildImapProperties(isOAuth = isOAuth, timeoutMs = "15000")
            val session = Session.getInstance(props)
            store = session.getStore("imaps")
            store.connect(IMAP_HOST, email, tokenOrPassword)

            sourceFolder = resolveSpecialUseFolder(store, folderType) ?: store.getFolder("INBOX")
            sourceFolder.open(Folder.READ_WRITE)

            val msg = findMessageByUidOrNumber(sourceFolder, uid)
            if (msg != null) {
                // もし All Mail フォルダが存在し、かつ現在 All Mail 以外にいる場合は念のためコピーを保証した上でソースから削除
                if (folderType != MailFolderType.ALL_MAIL) {
                    val allMailFolder = resolveSpecialUseFolder(store, MailFolderType.ALL_MAIL)
                    if (allMailFolder != null && !allMailFolder.fullName.equals(sourceFolder.fullName, ignoreCase = true)) {
                        try {
                            sourceFolder.copyMessages(arrayOf(msg), allMailFolder)
                        } catch (_: Exception) {}
                    }
                    msg.setFlag(Flags.Flag.DELETED, true)
                }
            }
            Result.success(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive message (UID=$uid): ${e.message}", e)
            Result.failure(e)
        } finally {
            try { if (sourceFolder?.isOpen == true) sourceFolder.close(true) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    /**
     * メールをゴミ箱（\Trash）へ移動（既にゴミ箱内にある場合は完全削除）
     */
    suspend fun moveToTrash(
        email: String,
        tokenOrPassword: String,
        uid: Long,
        isOAuth: Boolean = false,
        folderType: MailFolderType = MailFolderType.INBOX
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var sourceFolder: Folder? = null
        try {
            val props = buildImapProperties(isOAuth = isOAuth, timeoutMs = "15000")
            val session = Session.getInstance(props)
            store = session.getStore("imaps")
            store.connect(IMAP_HOST, email, tokenOrPassword)

            sourceFolder = resolveSpecialUseFolder(store, folderType) ?: store.getFolder("INBOX")
            sourceFolder.open(Folder.READ_WRITE)

            val msg = findMessageByUidOrNumber(sourceFolder, uid)
            if (msg != null) {
                if (folderType != MailFolderType.TRASH) {
                    val trashFolder = resolveSpecialUseFolder(store, MailFolderType.TRASH)
                    if (trashFolder != null && !trashFolder.fullName.equals(sourceFolder.fullName, ignoreCase = true)) {
                        sourceFolder.copyMessages(arrayOf(msg), trashFolder)
                    }
                }
                msg.setFlag(Flags.Flag.DELETED, true)
            }
            Result.success(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move message to Trash (UID=$uid): ${e.message}", e)
            Result.failure(e)
        } finally {
            try { if (sourceFolder?.isOpen == true) sourceFolder.close(true) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    /**
     * 作成中のメールを下書きトレイ（\Drafts）に保存
     */
    suspend fun saveDraft(
        context: Context,
        fromEmail: String,
        tokenOrPassword: String,
        toEmail: String,
        subject: String,
        bodyText: String,
        attachments: List<MailAttachment> = emptyList(),
        isOAuth: Boolean = false,
        signWithOpenPgp: Boolean = false,
        requestReadReceipt: Boolean = false
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var draftsFolder: Folder? = null
        try {
            val imapProps = buildImapProperties(isOAuth = isOAuth, timeoutMs = "15000")
            val imapSession = Session.getInstance(imapProps)
            store = imapSession.getStore("imaps")
            store.connect(IMAP_HOST, fromEmail, tokenOrPassword)

            draftsFolder = resolveSpecialUseFolder(store, MailFolderType.DRAFTS)
                ?: throw IllegalStateException("Drafts folder not found on server")

            val message = buildMimeMessage(
                context = context,
                session = imapSession,
                fromEmail = fromEmail,
                toEmail = toEmail,
                subject = subject,
                bodyText = bodyText,
                attachments = attachments,
                allowEmptyRecipient = true,
                signWithOpenPgp = signWithOpenPgp,
                requestReadReceipt = requestReadReceipt
            )
            message.setFlag(Flags.Flag.DRAFT, true)
            message.setFlag(Flags.Flag.SEEN, true)
            message.saveChanges()

            draftsFolder.open(Folder.READ_WRITE)
            draftsFolder.appendMessages(arrayOf(message))
            Log.i(TAG, "Saved draft to ${draftsFolder.fullName}")
            Result.success(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save draft: ${e.message}", e)
            Result.failure(e)
        } finally {
            try { if (draftsFolder?.isOpen == true) draftsFolder.close(false) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    private fun findMessageByUidOrNumber(folder: Folder, uid: Long): Message? {
        val imapFolder = folder as? IMAPFolder
        return if (imapFolder != null && uid > 0) {
            imapFolder.getMessageByUID(uid)
        } else if (uid in 1..folder.messageCount.toLong()) {
            folder.getMessage(uid.toInt())
        } else {
            null
        }
    }

    private fun buildMimeMessage(
        context: Context,
        session: Session,
        fromEmail: String,
        toEmail: String,
        subject: String,
        bodyText: String,
        attachments: List<MailAttachment>,
        allowEmptyRecipient: Boolean = false,
        signWithOpenPgp: Boolean = false,
        requestReadReceipt: Boolean = false
    ): MimeMessage {
        val normalizedTo = toEmail.replace(";", ",").trim()
        val parsedRecipients = if (normalizedTo.isNotEmpty()) {
            InternetAddress.parse(normalizedTo, false)
        } else {
            emptyArray()
        }
        if (!allowEmptyRecipient && parsedRecipients.isEmpty()) {
            throw IllegalArgumentException("Invalid recipient email address: $toEmail")
        }

        val finalBodyText = if (signWithOpenPgp && !bodyText.contains("-----BEGIN PGP SIGNED MESSAGE-----")) {
            OpenPgpManager.signCleartextMessage(context, fromEmail, bodyText)
        } else {
            bodyText
        }

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(fromEmail, "Shinji", "UTF-8"))
            if (parsedRecipients.isNotEmpty()) {
                setRecipients(Message.RecipientType.TO, parsedRecipients)
            }
            setSubject(subject, "UTF-8")
            sentDate = java.util.Date()
            if (requestReadReceipt) {
                setHeader("Disposition-Notification-To", "<$fromEmail>")
                setHeader("Return-Receipt-To", "<$fromEmail>")
                setHeader("X-Confirm-Reading-To", "<$fromEmail>")
            }
            if (signWithOpenPgp) {
                setHeader("X-Kapamilya-OpenPGP-PublicKey", OpenPgpManager.getPublicKeyBase64(context, fromEmail))
            }
        }

        if (attachments.isEmpty()) {
            message.setText(finalBodyText, "UTF-8")
        } else {
            val multipart = MimeMultipart("mixed")
            val textPart = MimeBodyPart().apply {
                setText(finalBodyText, "UTF-8")
            }
            multipart.addBodyPart(textPart)

            for (att in attachments) {
                try {
                    val attachPart = MimeBodyPart()
                    val dataSource = UriDataSource(context, att.uri, att.fileName, att.mimeType)
                    attachPart.dataHandler = DataHandler(dataSource)
                    attachPart.fileName = MimeUtility.encodeText(att.fileName, "UTF-8", null)
                    multipart.addBodyPart(attachPart)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to attach file ${att.fileName}: ${e.message}", e)
                }
            }
            message.setContent(multipart)
        }
        return message
    }

    /**
     * メール送信 ＆ 音声/画像/ファイル添付 ＆ OpenPGP電子署名 ＆ 開封確認通知(MDN)要求 ＆ 送信トレイ自動同期
     */
    suspend fun sendEmail(
        context: Context,
        fromEmail: String,
        tokenOrPassword: String,
        toEmail: String,
        subject: String,
        bodyText: String,
        attachments: List<MailAttachment> = emptyList(),
        isOAuth: Boolean = false,
        signWithOpenPgp: Boolean = false,
        requestReadReceipt: Boolean = false
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        var store: Store? = null
        try {
            val props = Properties().apply {
                for (proto in listOf("smtp", "smtps")) {
                    put("mail.$proto.host", SMTP_HOST)
                    put("mail.$proto.port", SMTP_PORT)
                    put("mail.$proto.auth", "true")
                    put("mail.$proto.ssl.enable", "true")
                    put("mail.$proto.ssl.checkserveridentity", "true")
                    put("mail.$proto.timeout", "30000")
                    put("mail.$proto.connectiontimeout", "30000")
                    put("mail.$proto.writetimeout", "30000")
                    if (isOAuth) {
                        put("mail.$proto.sasl.enable", "true")
                        put("mail.$proto.sasl.mechanisms", "XOAUTH2")
                        put("mail.$proto.auth.login.disable", "true")
                        put("mail.$proto.auth.plain.disable", "true")
                    }
                }
            }

            val session = Session.getInstance(props, object : javax.mail.Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(fromEmail, tokenOrPassword)
                }
            })

            val message = buildMimeMessage(
                context = context,
                session = session,
                fromEmail = fromEmail,
                toEmail = toEmail,
                subject = subject,
                bodyText = bodyText,
                attachments = attachments,
                allowEmptyRecipient = false,
                signWithOpenPgp = signWithOpenPgp,
                requestReadReceipt = requestReadReceipt
            )
            message.saveChanges()

            // SMTP送信
            if (isOAuth) {
                val transport = session.getTransport("smtps")
                try {
                    transport.connect(SMTP_HOST, fromEmail, tokenOrPassword)
                    transport.sendMessage(message, message.allRecipients)
                } finally {
                    try { transport.close() } catch (_: Exception) {}
                }
            } else {
                Transport.send(message)
            }
            Log.i(TAG, "Email successfully sent to $toEmail")

            // 送信済みトレイ（RFC 6154 \Sent）にコピー保存して公式Gmailと完全同期
            var sentFolder: Folder? = null
            try {
                val imapProps = buildImapProperties(isOAuth = isOAuth, timeoutMs = "15000")
                val imapSession = Session.getInstance(imapProps)
                store = imapSession.getStore("imaps")
                store.connect(IMAP_HOST, fromEmail, tokenOrPassword)

                sentFolder = resolveSpecialUseFolder(store, MailFolderType.SENT)
                if (sentFolder != null) {
                    sentFolder.open(Folder.READ_WRITE)
                    message.setFlag(Flags.Flag.SEEN, true)
                    sentFolder.appendMessages(arrayOf(message))
                    Log.i(TAG, "Successfully synced sent email to Gmail sent folder: ${sentFolder.fullName}")
                }
            } catch (sentEx: Exception) {
                Log.w(TAG, "Warning: Could not sync to sent folder: ${sentEx.message}")
            } finally {
                try { if (sentFolder?.isOpen == true) sentFolder.close(false) } catch (_: Exception) {}
            }

            Result.success(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send email: ${e.message}", e)
            Result.failure(e)
        } finally {
            try { store?.close() } catch (_: Exception) {}
        }
    }

    /**
     * RFC 8098 (Message Disposition Notification / MDN) 準拠の開封確認通知メールを返信送信
     */
    suspend fun sendReadReceiptMdn(
        context: Context,
        fromEmail: String,
        tokenOrPassword: String,
        receiptToAddress: String,
        originalSubject: String,
        originalMessageId: String,
        isOAuth: Boolean = false
    ): Result<Boolean> {
        val mdnSubject = "開封済み: $originalSubject (Read: $originalSubject)"
        val nowStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())
        val mdnBody = buildString {
            append("【開封確認通知 / Read Receipt (RFC 8098 MDN)】\r\n\r\n")
            append("以下のメールは受信者（$fromEmail）によって開封・表示されました。\r\n")
            append("Your message was displayed to the recipient ($fromEmail).\r\n\r\n")
            append("・件名 (Subject): $originalSubject\r\n")
            append("・開封日時 (Displayed At): $nowStr\r\n")
            if (originalMessageId.isNotBlank()) {
                append("・対象Message-ID (Original-Message-ID): $originalMessageId\r\n")
            }
            append("・処理ステータス (Disposition): manual-action/MDN-sent-manually; displayed\r\n")
            append("・クライアント (Reporting-UA): Kapamilya Serena Mail v5.0.0\r\n")
        }
        return sendEmail(
            context = context,
            fromEmail = fromEmail,
            tokenOrPassword = tokenOrPassword,
            toEmail = receiptToAddress,
            subject = mdnSubject,
            bodyText = mdnBody,
            attachments = emptyList(),
            isOAuth = isOAuth,
            signWithOpenPgp = OpenPgpManager.isSignByDefault(context),
            requestReadReceipt = false
        )
    }

    /**
     * MimeMessageからプレーンテキスト本文を抽出
     */
    private fun extractBodyText(part: Part): String {
        return try {
            when {
                part.isMimeType("text/plain") -> readPartTextSafely(part)
                part.isMimeType("text/html") -> {
                    val html = readPartTextSafely(part)
                    android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                }
                part.isMimeType("multipart/*") -> {
                    val content = part.content
                    if (content is MimeMultipart) {
                        extractTextFromMultipart(content)
                    } else {
                        ""
                    }
                }
                else -> {
                    val content = part.content
                    when (content) {
                        is String -> content
                        is MimeMultipart -> extractTextFromMultipart(content)
                        else -> ""
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract body text: ${e.message}")
            ""
        }
    }

    private fun readPartTextSafely(part: Part): String {
        return try {
            when (val content = part.content) {
                is String -> content
                is InputStream -> content.use { it.readBytes().toString(Charsets.UTF_8) }
                else -> content?.toString() ?: ""
            }
        } catch (e: UnsupportedEncodingException) {
            try {
                val contentType = part.contentType ?: ""
                val charsetName = Regex("charset=[\"]?([^;\"]+)", RegexOption.IGNORE_CASE)
                    .find(contentType)?.groupValues?.getOrNull(1)?.trim() ?: "UTF-8"
                val resolvedCharset = when (charsetName.lowercase()) {
                    "cp932", "ms932", "windows-31j", "shift_jis", "sjis" -> Charset.forName("Shift_JIS")
                    "iso-2022-jp" -> Charset.forName("ISO-2022-JP")
                    else -> Charsets.UTF_8
                }
                part.inputStream.use { it.readBytes().toString(resolvedCharset) }
            } catch (_: Exception) {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractTextFromMultipart(multipart: MimeMultipart): String {
        var htmlFallback: String? = null
        val count = try { multipart.count } catch (_: Exception) { 0 }
        for (i in 0 until count) {
            val bodyPart = try { multipart.getBodyPart(i) } catch (_: Exception) { continue }
            val disposition = try { bodyPart.disposition } catch (_: Exception) { null }
            if (Part.ATTACHMENT.equals(disposition, ignoreCase = true)) {
                continue
            }
            if (bodyPart.isMimeType("text/plain")) {
                val plain = readPartTextSafely(bodyPart)
                if (plain.isNotBlank()) return plain
            } else if (bodyPart.isMimeType("text/html")) {
                if (htmlFallback.isNullOrBlank()) {
                    val html = readPartTextSafely(bodyPart)
                    htmlFallback = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                }
            } else if (bodyPart.isMimeType("multipart/*")) {
                val nested = try { bodyPart.content as? MimeMultipart } catch (_: Exception) { null }
                if (nested != null) {
                    val text = extractTextFromMultipart(nested)
                    if (text.isNotBlank()) return text
                }
            }
        }
        return htmlFallback ?: ""
    }

    /**
     * Android Content Uri 用の DataSource 実装
     * オーディオ(.flac/.wav/.mp3)、画像、ドキュメント、Googleドライブ等を安全にストリーミング
     */
    private class UriDataSource(
        private val context: Context,
        private val uri: Uri,
        private val name: String,
        private val mime: String
    ) : DataSource {
        override fun getInputStream(): InputStream {
            return context.contentResolver.openInputStream(uri)
                ?: throw java.io.FileNotFoundException("Cannot open stream for URI: $uri")
        }

        override fun getOutputStream(): OutputStream {
            throw UnsupportedOperationException("Writing to attachment URI is not supported")
        }

        override fun getContentType(): String {
            return if (mime.isNotEmpty()) mime else "application/octet-stream"
        }

        override fun getName(): String {
            return name
        }
    }
}
