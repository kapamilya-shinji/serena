package com.kapamilya.serena.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kapamilya Serena Mail (com.kapamilya.serena.mail) Unit Test Suite
 */
class KapamilyaSerenaMailUnitTest {

    @Test
    fun testKapamilyaSerenaMailBrandingAndGemma4Attribution() {
        val appId = "com.kapamilya.serena.mail"
        val aiAttribution = "Gemma 4 (Developed by Google DeepMind)"
        val pillars = "Bayanihan • Magna Carta • Kapamilya"
        assertEquals("com.kapamilya.serena.mail", appId)
        assertTrue(aiAttribution.contains("Gemma 4"))
        assertTrue(aiAttribution.contains("Developed by Google DeepMind"))
        assertTrue(pillars.contains("Kapamilya"))
    }

    @Test
    fun testHorizonPhishingShieldAndOtpExtractor() {
        val sig = com.kapamilya.serena.mail.ai.Gemma4MailEngine.ENGINE_SIGNATURE
        assertTrue(sig.contains("v5.0"))

        val safeReport = com.kapamilya.serena.mail.ai.Gemma4MailEngine.inspectEmailSecurityAndExtractKeyItems(
            sender = "Google <no-reply@accounts.google.com>",
            subject = "確認コードのお知らせ",
            body = "あなたの確認コード: 849201 です。"
        )
        assertTrue(!safeReport.isSuspiciousPhishing)
        assertTrue(safeReport.extractedOtpCodes.contains("849201"))

        val phishingReport = com.kapamilya.serena.mail.ai.Gemma4MailEngine.inspectEmailSecurityAndExtractKeyItems(
            sender = "Security <alert@fake-bank.example>",
            subject = "緊急の本人確認：アカウントが凍結されました",
            body = "24時間以内に http://fake-bank.example/login から口座情報を更新してください。"
        )
        assertTrue(phishingReport.isSuspiciousPhishing)

        val acceptDraft = com.kapamilya.serena.mail.ai.Gemma4MailEngine.generateSmartReplyByIntent(
            originalSender = "田中太郎 <tanaka@example.com>",
            originalSubject = "打ち合わせ日程の件",
            intentType = "ACCEPT"
        )
        assertTrue(acceptDraft.contains("田中太郎"))
    }

    @Test
    fun testUrlTrailingPunctuationCleaningAndEmptySenderFallback() {
        val report = com.kapamilya.serena.mail.ai.Gemma4MailEngine.inspectEmailSecurityAndExtractKeyItems(
            sender = "Support <support@example.com>",
            subject = "Details link",
            body = "Please check (https://example.com/status?id=100)。"
        )
        assertTrue(report.extractedUrls.contains("https://example.com/status?id=100"))

        val fallbackReply = com.kapamilya.serena.mail.ai.Gemma4MailEngine.generateSmartReplyByIntent(
            originalSender = "<no-name@example.com>",
            originalSubject = "Schedule",
            intentType = "QUESTION"
        )
        assertTrue(fallbackReply.isNotBlank())
    }

    @Test
    fun testEmbeddingGemma2AndGemma4MailUnifiedPipeline() {
        val phishingReport = com.kapamilya.serena.mail.ai.Gemma4MailEngine.inspectEmailSecurityAndExtractKeyItems(
            sender = "Security <alert@fake-bank.example>",
            subject = "緊急の本人確認：アカウントが凍結されました",
            body = "24時間以内にパスワードを再設定し口座情報を更新してください。"
        )
        assertTrue(phishingReport.isSuspiciousPhishing)
        assertTrue(phishingReport.embeddingGemma2PhishingSimilarity > 0f)

        val classifiedDecline = com.kapamilya.serena.mail.ai.EmbeddingGemma2Engine.classifyReplyIntentSemantic(
            subject = "イベント参加のお誘い",
            body = "来週のイベントにご参加いただけますか？",
            intentHint = "スケジュール都合により辞退・見送り"
        )
        assertEquals("DECLINE", classifiedDecline)

        val naturalIntentReply = com.kapamilya.serena.mail.ai.Gemma4MailEngine.generateSmartReplyByIntent(
            originalSender = "佐藤花子 <sato@example.com>",
            originalSubject = "進捗確認の件",
            intentType = "その後の進捗はいかがでしょうか リマインド"
        )
        assertTrue(naturalIntentReply.contains("佐藤花子"))
    }
}
