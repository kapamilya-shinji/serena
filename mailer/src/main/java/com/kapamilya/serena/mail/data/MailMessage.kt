package com.kapamilya.serena.mail.data

import java.io.Serializable
import java.util.Date

/**
 * RFC 6154 (IMAP LIST Extension for Special-Use Mailboxes) 準拠の標準フォルダ種別
 */
enum class MailFolderType(
    val specialUseAttributes: List<String>,
    val fallbackNames: List<String>
) {
    INBOX(
        specialUseAttributes = listOf("\\Inbox"),
        fallbackNames = listOf("INBOX")
    ),
    STARRED(
        specialUseAttributes = listOf("\\Flagged"),
        fallbackNames = listOf("[Gmail]/Starred", "[Gmail]/スター付き", "[Gmail]/Destacados", "[Gmail]/Met ster", "Starred", "Flagged")
    ),
    SENT(
        specialUseAttributes = listOf("\\Sent"),
        fallbackNames = listOf("[Gmail]/Sent Mail", "[Gmail]/送信済みメール", "[Gmail]/送信済みトレイ", "[Gmail]/Enviados", "[Gmail]/Verzonden", "Sent", "Sent Messages", "Sent Items")
    ),
    DRAFTS(
        specialUseAttributes = listOf("\\Drafts"),
        fallbackNames = listOf("[Gmail]/Drafts", "[Gmail]/下書き", "[Gmail]/Borradores", "[Gmail]/Concepten", "Drafts")
    ),
    ALL_MAIL(
        specialUseAttributes = listOf("\\All", "\\Archive"),
        fallbackNames = listOf("[Gmail]/All Mail", "[Gmail]/すべてのメール", "[Gmail]/Todos", "[Gmail]/Alle e-mail", "Archive", "All Mail")
    ),
    SPAM(
        specialUseAttributes = listOf("\\Junk"),
        fallbackNames = listOf("[Gmail]/Spam", "[Gmail]/迷惑メール", "Spam", "Junk")
    ),
    TRASH(
        specialUseAttributes = listOf("\\Trash"),
        fallbackNames = listOf("[Gmail]/Trash", "[Gmail]/ゴミ箱", "[Gmail]/Papelera", "[Gmail]/Prullenbak", "Trash", "Deleted Items", "Deleted Messages")
    )
}

/**
 * Kapamilya Serena Mail メールメッセージモデル
 * (RFC 6154 7フォルダ + RFC 4880 OpenPGP 電子署名 + RFC 8098 開封確認通知 MDN 完全対応)
 */
data class MailMessage(
    val messageId: String,
    val sender: String,
    val senderAddress: String,
    val recipients: List<String>,
    val subject: String,
    val body: String,
    val receivedDate: Date?,
    var isUnread: Boolean,
    val uid: Long = -1L,
    val snippet: String = body.take(120).replace("\n", " ").trim(),
    var isStarred: Boolean = false,
    val folderType: MailFolderType = MailFolderType.INBOX,
    val readReceiptRequestedTo: String = "",
    val isReadReceiptMdn: Boolean = false,
    val hasOpenPgpSignature: Boolean = body.contains("-----BEGIN PGP SIGNED MESSAGE-----"),
    val openPgpPubKeyHeader: String = "",
    val rfcMessageIdHeader: String = ""
) : Serializable
