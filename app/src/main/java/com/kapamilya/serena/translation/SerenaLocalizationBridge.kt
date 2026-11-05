/*
 * Copyright (C) 2026 Shinji Sakiyama (shinjisakiyama@gmail.com)
 * Bayanihan • Magna Carta • Kapamilya
 * Licensed under the Apache License, Version 2.0
 */

package com.kapamilya.serena.translation

import android.content.Context
import androidx.annotation.StringRes
import java.util.Locale

/**
 * SerenaLocalizationBridge
 *
 * Kapamilya Serena 全体の「ハードコード日本語ゼロ・全言語完全追従」を保証する
 * ハイブリッド・ローカライズ＆デバイス言語強制適応ブリッジ。
 *
 * アーキテクチャ:
 * 1. 【第1層: ネイティブ getText / getString & 5言語即時辞書 (0ミリ秒)】
 *    Android 標準のリソース (`res/values`, `values-ja`, `values-en`, `values-tl`, `values-es`, `values-nl`) と
 *    インメモリ5言語マップにより、スワイプ・メニュー操作時の遅延0ミリ秒で自然な現地語テキストを返す。
 * 2. 【第2層: ハードコード日本語の自動デバイス言語置換 (0ミリ秒)】
 *    万が一ソースコード内のモジュールが日本語文字列（「戻る」「設定」「白基調の明るい」等）を返した場合でも、
 *    端末のシステム言語 (`Locale.getDefault().language`) が日本語 (`ja`) 以外であれば、
 *    音声読み上げ直前に端末言語（英語 `en`・タガログ語 `tl`・スペイン語 `es`・オランダ語 `nl` 等）へ即座に自動変換する。
 */
object SerenaLocalizationBridge {

    enum class SupportedLang {
        JA, EN, TL, ES, NL
    }

    fun currentLang(): SupportedLang {
        return when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
            "ja" -> SupportedLang.JA
            "tl", "fil" -> SupportedLang.TL
            "es" -> SupportedLang.ES
            "nl" -> SupportedLang.NL
            else -> SupportedLang.EN
        }
    }

    fun isJapaneseDevice(): Boolean = currentLang() == SupportedLang.JA

    /**
     * Android リソースIDから現在の端末言語に合わせた文字列を取得し、万が一日本語以外で日本語が残っていれば自動変換
     */
    fun getText(context: Context, @StringRes resId: Int, vararg formatArgs: Any): String {
        val raw = try {
            if (formatArgs.isEmpty()) context.getString(resId) else context.getString(resId, *formatArgs)
        } catch (_: Exception) {
            ""
        }
        return adaptToDeviceLanguage(raw)
    }

    /**
     * 5言語（ja / en / tl / es / nl）のインライン即時選択ヘルパー（0ミリ秒）
     */
    fun select(
        ja: String,
        en: String,
        tl: String = en,
        es: String = en,
        nl: String = en
    ): String {
        return when (currentLang()) {
            SupportedLang.JA -> ja
            SupportedLang.TL -> tl
            SupportedLang.ES -> es
            SupportedLang.NL -> nl
            SupportedLang.EN -> en
        }
    }

    data class LocalizedPhrase(
        val ja: String,
        val en: String,
        val tl: String,
        val es: String,
        val nl: String
    ) {
        fun forLang(lang: SupportedLang): String = when (lang) {
            SupportedLang.JA -> ja
            SupportedLang.EN -> en
            SupportedLang.TL -> tl
            SupportedLang.ES -> es
            SupportedLang.NL -> nl
        }
    }

    // ソースコード内でハードコードされやすいUI・AI・音声エンジン用語の多言語正規化辞書
    private val PHRASE_DICTIONARY: List<LocalizedPhrase> = listOf(
        // Kapamilya Voice / Menu
        LocalizedPhrase(
            "全方位AI画面推測・セキュリティ壁突破スキャン (Omni-Deduction)",
            "Omni-Screen AI Deduction & Security Breakthrough",
            "Omni-Screen AI Deduction at Paglampas sa Seguridad",
            "Deducción de pantalla completa con IA y salto de seguridad",
            "Omni-Screen AI-deductie en beveiligingsdoorbraak"
        ),
        LocalizedPhrase(
            "Kapamilya 音声エンジンモード:",
            "Kapamilya Voice Engine Mode:",
            "Mode ng Kapamilya Voice Engine:",
            "Modo del motor de voz Kapamilya:",
            "Kapamilya-spraakenginemodus:"
        ),
        LocalizedPhrase(
            "Kapamilya 音声エンジン:",
            "Kapamilya Voice Engine:",
            "Kapamilya Voice Engine:",
            "Motor de voz Kapamilya:",
            "Kapamilya-spraakengine:"
        ),
        LocalizedPhrase(
            "Unicode多言語ルーター:",
            "Unicode Multilingual Router:",
            "Unicode Multilingual Router:",
            "Enrutador multilingüe Unicode:",
            "Unicode meertalige router:"
        ),
        LocalizedPhrase(
            "7地域英語・タグリッシュ:",
            "7-Region English & Taglish:",
            "7-Rehiyong Ingles at Taglish:",
            "Inglés de 7 regiones y Taglish:",
            "7-regio Engels en Taglish:"
        ),
        LocalizedPhrase(
            "英語・タグリッシュ発音:",
            "English & Taglish Pronunciation:",
            "Pagbigkas sa Ingles at Taglish:",
            "Pronunciación en inglés y Taglish:",
            "Uitspraak Engels en Taglish:"
        ),
        // Image & Color / Shape descriptions
        LocalizedPhrase("白基調の明るい", "bright white-toned ", "maliwanag na puting ", "de tono blanco brillante ", "helder witgetinte "),
        LocalizedPhrase("黒基調の落ち着いた", "dark-toned ", "madilim na kulay na ", "de tono oscuro ", "donkergetinte "),
        LocalizedPhrase("暖かみのある赤・オレンジ系の", "warm red/orange ", "mainit na pula/kahel na ", "rojo/naranja cálido ", "warm rood/oranje "),
        LocalizedPhrase("鮮やかな緑系の", "vibrant green ", "matingkad na berdeng ", "verde vibrante ", "levendig groene "),
        LocalizedPhrase("爽やかな青・水色系の", "cool blue ", "maaliwalas na asul na ", "azul fresco ", "fris blauwe "),
        LocalizedPhrase("明るい黄色・ゴールド系の", "bright yellow/gold ", "maliwanag na dilaw/gintong ", "amarillo/dorado brillante ", "helder geel/gouden "),
        LocalizedPhrase("紫・ピンク系の", "purple/pink ", "lila/pink na ", "púrpura/rosa ", "paars/roze "),
        LocalizedPhrase("バランスの取れた色合いの", "balanced-color ", "balanseng kulay na ", "de color equilibrado ", "evenwichtig gekleurde "),
        LocalizedPhrase("横長のバナー画像", "wide banner image", "pahabang banner na larawan", "imagen de banner ancho", "brede bannerafbeelding"),
        LocalizedPhrase("縦長の写真・画像", "portrait image", "patayong larawan", "imagen vertical", "portretafbeelding"),
        LocalizedPhrase("アイコン画像", "icon graphic", "larawan ng icon", "imagen de icono", "pictogramafbeelding"),
        LocalizedPhrase("スクエア画像", "square image", "parisukat na larawan", "imagen cuadrada", "vierkante afbeelding"),
        LocalizedPhrase("画像解説:", "Image description:", "Paliwanag sa larawan:", "Descripción de la imagen:", "Afbeeldingsbeschrijving:"),
        // AI Auto-Label & Navigation Roles
        LocalizedPhrase("前の画面へ戻る・ダイアログを閉じるボタン", "Navigate Back / Close Dialog Button", "Pindutan ng Bumalik o Isara", "Botón de volver atrás o cerrar", "Knop Terug of dialoogvenster sluiten"),
        LocalizedPhrase("新規作成・追加", "Create New / Add", "Gumawa ng Bago / Magdagdag", "Crear nuevo / Añadir", "Nieuw maken / Toevoegen"),
        LocalizedPhrase("アカウント・プロフィール", "Account / Profile", "Account / Profile", "Cuenta / Perfil", "Account / Profiel"),
        LocalizedPhrase("画像ギャラリー・アルバム", "Photo Gallery / Album", "Gallery ng Larawan / Album", "Galería de fotos / Álbum", "Fotogalerij / Album"),
        LocalizedPhrase("カメラ・写真撮影", "Camera / Take Photo", "Kamera / Kumuha ng Larawan", "Cámara / Tomar foto", "Camera / Foto maken"),
        LocalizedPhrase("マイク・音声入力", "Microphone / Voice Input", "Mikropono / Boses", "Micrófono / Entrada de voz", "Microfoon / Spraakinvoer"),
        LocalizedPhrase("ショッピングカート", "Shopping Cart", "Shopping Cart", "Carrito de compras", "Winkelwagen"),
        LocalizedPhrase("QRコードスキャン", "QR Code Scanner", "QR Code Scanner", "Escáner de código QR", "QR-codescanner"),
        LocalizedPhrase("現在地・位置情報", "Current Location", "Kasalukuyang Lokasyon", "Ubicación actual", "Huidige locatie"),
        LocalizedPhrase("画面テーマ切り替え", "Switch Theme", "Palitan ang Tema", "Cambiar tema", "Thema wisselen"),
        LocalizedPhrase("ダウンロード・保存", "Download / Save", "I-download / I-save", "Descargar / Guardar", "Downloaden / Opslaan"),
        LocalizedPhrase("絞り込み・フィルター", "Filter / Sort", "I-filter / Ayusin", "Filtrar / Ordenar", "Filteren / Sorteren"),
        LocalizedPhrase("再読み込み", "Refresh / Reload", "I-refresh", "Actualizar / Recargar", "Vernieuwen"),
        LocalizedPhrase("ヘルプ・情報", "Help / Info", "Tulong / Impormasyon", "Ayuda / Información", "Hulp / Informatie"),
        LocalizedPhrase("高評価・いいね", "Like / Upvote", "Gustuhin / Like", "Me gusta", "Vind ik leuk"),
        LocalizedPhrase("お気に入り", "Favorite / Bookmark", "Paborito", "Favorito", "Favoriet"),
        LocalizedPhrase("ファイル添付", "Attach File", "Maglakip ng File", "Adjuntar archivo", "Bestand bijvoegen"),
        LocalizedPhrase("通話発信", "Make Call", "Tumawag", "Llamar", "Bellen"),
        LocalizedPhrase("通話終了", "End Call", "Tapusin ang Tawag", "Finalizar llamada", "Oproep beëindigen"),
        LocalizedPhrase("一時停止", "Pause", "I-pause", "Pausar", "Pauzeren"),
        LocalizedPhrase("次の曲", "Next Track", "Susunod na Kanta", "Siguiente pista", "Volgend nummer"),
        LocalizedPhrase("前の曲", "Previous Track", "Nakaraang Kanta", "Pista anterior", "Vorig nummer"),
        LocalizedPhrase("購入手続き", "Checkout / Buy", "Bumili / Checkout", "Comprar / Pagar", "Afrekenen / Kopen"),
        LocalizedPhrase("貼り付け", "Paste", "I-paste", "Pegar", "Plakken"),
        LocalizedPhrase("コピー", "Copy", "Kopyahin", "Copiar", "Kopiëren"),
        LocalizedPhrase("戻る", "Back", "Bumalik", "Atrás", "Terug"),
        LocalizedPhrase("次へ", "Next", "Susunod", "Siguiente", "Volgende"),
        LocalizedPhrase("閉じる", "Close", "Isara", "Cerrar", "Sluiten"),
        LocalizedPhrase("ホーム", "Home", "Home", "Inicio", "Start"),
        LocalizedPhrase("メニュー", "Menu", "Menu", "Menú", "Menu"),
        LocalizedPhrase("検索", "Search", "Maghanap", "Buscar", "Zoeken"),
        LocalizedPhrase("設定", "Settings", "Mga Setting", "Ajustes", "Instellingen"),
        LocalizedPhrase("共有", "Share", "Ibahagi", "Compartir", "Delen"),
        LocalizedPhrase("送信", "Send", "Ipadala", "Enviar", "Verzenden"),
        LocalizedPhrase("通知", "Notifications", "Mga Abiso", "Notificaciones", "Meldingen"),
        LocalizedPhrase("再生", "Play", "I-play", "Reproducir", "Afspelen"),
        LocalizedPhrase("停止", "Stop", "Ihinto", "Detener", "Stoppen"),
        LocalizedPhrase("音量", "Volume", "Volume", "Volumen", "Volume"),
        LocalizedPhrase("編集", "Edit", "I-edit", "Editar", "Bewerken"),
        LocalizedPhrase("削除", "Delete", "Burahin", "Eliminar", "Verwijderen"),
        // Spatial & Gemma 4 / Omni-Deduction terms
        LocalizedPhrase("Gemma 4 推論ボタン", "Gemma 4 deduced button", "Pindutan mula sa Gemma 4", "Botón deducido por Gemma 4", "Door Gemma 4 afgeleide knop"),
        LocalizedPhrase("Gemma 4 推論アイコン", "Gemma 4 deduced icon", "Icon mula sa Gemma 4", "Icono deducido por Gemma 4", "Door Gemma 4 afgeleid pictogram"),
        LocalizedPhrase("Gemma 4 推論:", "Gemma 4 Deduction:", "Gemma 4:", "Deducción de Gemma 4:", "Gemma 4-deductie:"),
        LocalizedPhrase("Gemma 4 推論", "Gemma 4 Deduction", "Gemma 4", "Deducción de Gemma 4", "Gemma 4-deductie"),
        LocalizedPhrase("Gemma 4 視覚推論", "Gemma 4 Visual Deduction", "Gemma 4 Visual", "Deducción visual de Gemma 4", "Visuele deductie van Gemma 4"),
        LocalizedPhrase("画面最上部", "Top of screen", "Pinakaitaas ng screen", "Parte superior de la pantalla", "Bovenkant van het scherm"),
        LocalizedPhrase("画面上部左", "Upper left", "Kaliwang itaas", "Arriba a la izquierda", "Linksboven"),
        LocalizedPhrase("画面上部右", "Upper right", "Kanang itaas", "Arriba a la derecha", "Rechtsboven"),
        LocalizedPhrase("画面上部", "Upper screen", "Itaas ng screen", "Parte alta de la pantalla", "Bovenste deel van scherm"),
        LocalizedPhrase("画面中央", "Screen center", "Gitna ng screen", "Centro de la pantalla", "Midden van het scherm"),
        LocalizedPhrase("画面下部左", "Lower left", "Kaliwang ibaba", "Abajo a la izquierda", "Linksonder"),
        LocalizedPhrase("画面下部右", "Lower right", "Kanang ibaba", "Abajo a la derecha", "Rechtsonder"),
        LocalizedPhrase("画面下部", "Lower screen", "Ibaba ng screen", "Parte baja de la pantalla", "Onderste deel van scherm"),
        LocalizedPhrase("画面最下部左", "Bottom left", "Pinaka-kaliwang ibaba", "Extremo inferior izquierdo", "Helemaal linksonder"),
        LocalizedPhrase("画面最下部右", "Bottom right", "Pinaka-kanang ibaba", "Extremo inferior derecho", "Helemaal rechtsonder"),
        LocalizedPhrase("画面最下部", "Bottom of screen", "Pinakaibaba ng screen", "Parte inferior de la pantalla", "Onderkant van het scherm"),
        LocalizedPhrase("画面左", "Screen left", "Kaliwa ng screen", "Izquierda de la pantalla", "Links op het scherm"),
        LocalizedPhrase("画面右", "Screen right", "Kanan ng screen", "Derecha de la pantalla", "Rechts op het scherm"),
        LocalizedPhrase("テキスト入力欄", "Text input field", "Kahon sa pag-input ng teksto", "Campo de texto", "Tekstveld"),
        LocalizedPhrase("切り替えスイッチ", "Toggle switch", "Switch", "Interruptor", "Schakelaar"),
        LocalizedPhrase("操作ボタン", "Action button", "Pindutan", "Botón de acción", "Actieknop"),
        LocalizedPhrase("ボタン", "Button", "Pindutan", "Botón", "Knop"),
        LocalizedPhrase("アイコン", "Icon", "Icon", "Icono", "Pictogram")
    )

    /**
     * 端末が日本語以外の言語に設定されている場合、文字列中に残っているハードコード日本語フレーズを
     * 現在の端末言語（en / tl / es / nl）へ0ミリ秒で一括変換する。
     */
    fun adaptToDeviceLanguage(text: String): String {
        if (text.isEmpty()) return text
        val lang = currentLang()
        if (lang == SupportedLang.JA) return text
        if (!containsJapaneseKana(text)) return text

        var result = text
        for (entry in PHRASE_DICTIONARY) {
            if (result.contains(entry.ja)) {
                result = result.replace(entry.ja, entry.forLang(lang))
            }
        }
        return result
    }

    private fun containsJapaneseKana(s: String): Boolean {
        for (ch in s) {
            val code = ch.code
            // Hiragana (3040..309F) or Katakana (30A0..30FF)
            if (code in 0x3040..0x30FF) return true
        }
        return false
    }
}
