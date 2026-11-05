package com.kapamilya.serena.ime

import java.util.Locale

/**
 * Serena IME / ScreenReader - クアッド言語＆全角半角ハイブリッド・フォネティック読み専門エンジン
 * - 全角文字・日本語文字（漢字・ひらがな・カタカナ・全角英数字・全角記号）: 常に日本語で「全角」「かな種別」を明確に識別して詳細読み
 * - 半角文字（A-Z, a-z, Ñ/ñ, ₱, 0-9, 半角記号）:
 *   - 🇯🇵 日本語環境: NATO通話表 ＋ タガログ語 ＋ ビサヤ語のマルチガイド（日本語TTS）
 *   - 🇵🇭 タガログ語/フィリピン語環境 (tl / fil): ネイティブ・タガログ語＆ビサヤ語フォネティック（Malaking titik / Maliit na titik / Bilang...）（フィリピン語TTS）
 *   - 🇺🇸 英語環境 (en): NATO Phonetic（Capital / Lowercase...）（英語TTS）
 */
object SerenaPhoneticEngine {

    /**
     * Serena IME 側で現在選択されている言語コード（"ja", "tl", "en" など）。
     * null の場合はシステムのデフォルト言語または呼び出し元の言語設定に従う。
     */
    @Volatile
    var activeImeLanguageCode: String? = null

    // NATO 欧文フォネティックコード (日本語カタカナ併記)
    private val natoPhoneticMap = mapOf(
        'A' to "Alpha（アルファ）",
        'B' to "Bravo（ブラボー）",
        'C' to "Charlie（チャーリー）",
        'D' to "Delta（デルタ）",
        'E' to "Echo（エコー）",
        'F' to "Foxtrot（フォックストロット）",
        'G' to "Golf（ゴルフ）",
        'H' to "Hotel（ホテル）",
        'I' to "India（インド）",
        'J' to "Juliet（ジュリエット）",
        'K' to "Kilo（キーロ）",
        'L' to "Lima（リマ）",
        'M' to "Mike（マイク）",
        'N' to "November（ノーベンバー）",
        'O' to "Oscar（オスカー）",
        'P' to "Papa（パパ）",
        'Q' to "Quebec（ケベック）",
        'R' to "Romeo（ロメオ）",
        'S' to "Sierra（シエラ）",
        'T' to "Tango（タンゴ）",
        'U' to "Uniform（ユニフォーム）",
        'V' to "Victor（ビクター）",
        'W' to "Whiskey（ウイスキー）",
        'X' to "X-ray（エックスレイ）",
        'Y' to "Yankee（ヤンキー）",
        'Z' to "Zulu（ズールー）"
    )

    // NATO 英語ネイティブ用マップ
    private val natoEnglishMap = mapOf(
        'A' to "Alpha", 'B' to "Bravo", 'C' to "Charlie", 'D' to "Delta", 'E' to "Echo",
        'F' to "Foxtrot", 'G' to "Golf", 'H' to "Hotel", 'I' to "India", 'J' to "Juliet",
        'K' to "Kilo", 'L' to "Lima", 'M' to "Mike", 'N' to "November", 'O' to "Oscar",
        'P' to "Papa", 'Q' to "Quebec", 'R' to "Romeo", 'S' to "Sierra", 'T' to "Tango",
        'U' to "Uniform", 'V' to "Victor", 'W' to "Whiskey", 'X' to "X-ray", 'Y' to "Yankee",
        'Z' to "Zulu"
    )

    // タガログ語 (Tagalog / Filipino) フォネティック辞書（日本語解説付き）
    private val tagalogPhoneticMap = mapOf(
        'A' to "Araw（太陽）のA",
        'B' to "Bahay（家）のB",
        'C' to "CaloocanのC",
        'D' to "Dagat（海）のD",
        'E' to "Eroplano（飛行機）のE",
        'F' to "FilipinoのF",
        'G' to "Gatas（ミルク）のG",
        'H' to "Halaman（植物）のH",
        'I' to "Ibon（鳥）のI",
        'J' to "JeepneyのJ",
        'K' to "Kamay（手）のK",
        'L' to "Langit（空・天国）のL",
        'M' to "Maganda / Mahal kita（愛してる）のM",
        'N' to "Nanay（お母さん）のN",
        'O' to "Oras（時間）のO",
        'P' to "Puso（心・愛）のP",
        'Q' to "QuezonのQ",
        'R' to "Rosas（バラ）のR",
        'S' to "Salamat（ありがとう）のS",
        'T' to "Tao（人間）のT",
        'U' to "Ulan（雨）のU",
        'V' to "VintaのV",
        'W' to "Watawat（国旗）のW",
        'X' to "X-rayのX",
        'Y' to "Yakap（抱きしめる）のY",
        'Z' to "ZamboangaのZ"
    )

    // タガログ語ネイティブ音声用マップ (Filipino TTS 用)
    private val tagalogNativeWordMap = mapOf(
        'A' to "Araw",
        'B' to "Bahay",
        'C' to "Caloocan",
        'D' to "Dagat",
        'E' to "Eroplano",
        'F' to "Filipino",
        'G' to "Gatas",
        'H' to "Halaman",
        'I' to "Ibon",
        'J' to "Jeepney",
        'K' to "Kamay",
        'L' to "Langit",
        'M' to "Mahal kita",
        'N' to "Nanay",
        'O' to "Oras",
        'P' to "Puso",
        'Q' to "Quezon",
        'R' to "Rosas",
        'S' to "Salamat",
        'T' to "Tao",
        'U' to "Ulan",
        'V' to "Vinta",
        'W' to "Watawat",
        'X' to "X-ray",
        'Y' to "Yakap",
        'Z' to "Zamboanga"
    )

    // ビサヤ語 / セブアノ語 (Bisaya / Cebuano) フォネティック辞書（日本語解説付き）
    private val bisayaPhoneticMap = mapOf(
        'A' to "Adlaw（太陽・日）のA",
        'B' to "Balay（家）のB",
        'C' to "Cebu（セブ）のC",
        'D' to "Dako（大きい）のD",
        'E' to "Eskwela（学校）のE",
        'F' to "FiestaのF",
        'G' to "Gwapa（美しい・可愛い）のG",
        'H' to "Higugma / Gugma（愛）のH",
        'I' to "Iro（犬）のI",
        'J' to "JollibeeのJ",
        'K' to "Kasingkasing（心）のK",
        'L' to "Lami（美味しい）のL",
        'M' to "Maayo（素晴らしい・良い）のM",
        'N' to "Nindot（素敵・綺麗）のN",
        'O' to "OspitalのO",
        'P' to "Palangga（最愛の人）のP",
        'Q' to "QuickのQ",
        'R' to "RadyoのR",
        'S' to "Salamat（ありがとう）のS",
        'T' to "Tubig（水）のT",
        'U' to "Ulan（雨）のU",
        'V' to "VictoryのV",
        'W' to "WalaのW",
        'X' to "XylophoneのX",
        'Y' to "Yuta（大地）のY",
        'Z' to "ZeroのZ"
    )

    // ビサヤ語ネイティブ音声用マップ
    private val bisayaNativeWordMap = mapOf(
        'A' to "Adlaw", 'B' to "Balay", 'C' to "Cebu", 'D' to "Dako", 'E' to "Eskwela",
        'F' to "Fiesta", 'G' to "Gwapa", 'H' to "Higugma", 'I' to "Iro", 'J' to "Jollibee",
        'K' to "Kasingkasing", 'L' to "Lami", 'M' to "Maayo", 'N' to "Nindot", 'O' to "Ospital",
        'P' to "Palangga", 'Q' to "Quick", 'R' to "Radyo", 'S' to "Salamat", 'T' to "Tubig",
        'U' to "Ulan", 'V' to "Victory", 'W' to "Wala", 'X' to "Xylophone", 'Y' to "Yuta",
        'Z' to "Zero"
    )

    // タガログ語数字読みマップ
    private val tagalogDigitMap = mapOf(
        '0' to "Sero", '1' to "Isa", '2' to "Dalawa", '3' to "Tatlo", '4' to "Apat",
        '5' to "Lima", '6' to "Anim", '7' to "Pito", '8' to "Walo", '9' to "Siyam"
    )

    // 和文通話表ベースのカナルーツ
    private val kanaBaseDescMap = mapOf(
        'ア' to "朝のあ", 'イ' to "いろはのい", 'ウ' to "上野のう", 'エ' to "英語のえ", 'オ' to "大阪のお",
        'カ' to "為替のか", 'キ' to "切手のき", 'ク' to "クラブのく", 'ケ' to "景気のけ", 'コ' to "子供のこ",
        'サ' to "桜のさ", 'シ' to "新聞のし", 'ス' to "すずめのす", 'セ' to "世界のせ", 'ソ' to "そろばんのそ",
        'タ' to "煙草のた", 'チ' to "千鳥のち", 'ツ' to "月夜のつ", 'テ' to "手紙のて", 'ト' to "東京のと",
        'ナ' to "名古屋のな", 'ニ' to "日本のに", 'ヌ' to "沼津のぬ", 'ネ' to "ねずみのね", 'ノ' to "野原のの",
        'ハ' to "はがきのは", 'ヒ' to "飛行機のひ", 'フ' to "富士山のふ", 'ヘ' to "平和のへ", 'ホ' to "保険のほ",
        'マ' to "マッチのま", 'ミ' to "三笠のみ", 'ム' to "無線のむ", 'メ' to "明治のめ", 'モ' to "もみじのも",
        'ヤ' to "大和のや", 'ユ' to "弓矢のゆ", 'ヨ' to "吉野のよ",
        'ラ' to "ラジオのら", 'リ' to "りんごのり", 'ル' to "留守居のる", 'レ' to "れんげのれ", 'ロ' to "ローマのろ",
        'ワ' to "わらびのわ", 'ヰ' to "井戸のゐ", 'ヱ' to "かぎのゑ", 'ヲ' to "尾張のを", 'ン' to "おしまいのん",
        'ガ' to "為替のが・濁点", 'ギ' to "切手のぎ・濁点", 'グ' to "クラブのぐ・濁点", 'ゲ' to "景気のげ・濁点", 'ゴ' to "子供のご・濁点",
        'ザ' to "桜のざ・濁点", 'ジ' to "新聞のじ・濁点", 'ズ' to "すずめのず・濁点", 'ゼ' to "世界のぜ・濁点", 'ゾ' to "そろばんのぞ・濁点",
        'ダ' to "煙草のだ・濁点", 'ヂ' to "千鳥のぢ・濁点", 'ヅ' to "月夜のづ・濁点", 'デ' to "手紙ので・濁点", 'ド' to "東京のど・濁点",
        'バ' to "はがきのば・濁点", 'ビ' to "飛行機のび・濁点", 'ブ' to "富士山のぶ・濁点", 'ベ' to "平和のべ・濁点", 'ボ' to "保険のぼ・濁点",
        'パ' to "はがきのぱ・半濁点", 'ピ' to "飛行機のぴ・半濁点", 'プ' to "富士山のぷ・半濁点", 'ペ' to "平和のぺ・半濁点", 'ポ' to "保険のぽ・半濁点",
        'ぁ' to "小文字のぁ", 'ぃ' to "小文字のぃ", 'ぅ' to "小文字のぅ", 'ぇ' to "小文字のぇ", 'ぉ' to "小文字のぉ",
        'ァ' to "小文字のア", 'ィ' to "小文字のイ", 'ゥ' to "小文字のウ", 'ェ' to "小文字のエ", 'ォ' to "小文字のオ",
        'っ' to "小文字のっ", 'ッ' to "小文字のツ",
        'ゃ' to "小文字のゃ", 'ャ' to "小文字のヤ", 'ゅ' to "小文字のゅ", 'ュ' to "小文字のユ", 'ょ' to "小文字のょ", 'ョ' to "小文字のヨ",
        'ゎ' to "小文字のゎ", 'ヮ' to "小文字のワ"
    )

    /**
     * 文字のフォネティック詳細読みテキストを取得
     */
    fun getPhoneticReading(char: Char, preferredLang: String? = null): String {
        return getPhoneticReadingWithLocale(char, preferredLang).first
    }

    /**
     * 文字のフォネティック詳細読みテキストと最適なTTS Localeのペアを取得
     * - 全角文字・日本語文字（漢字・かな・全角英数字・全角記号）は常に日本語（Locale.JAPANESE）
     * - 半角英数字・記号は、環境言語（タガログ語 / 英語 / 日本語）に合わせて自動切り替え
     */
    fun getPhoneticReadingWithLocale(char: Char, preferredLang: String? = null): Pair<String, Locale> {
        return try {
            val code = char.code
            val langCode = (preferredLang ?: activeImeLanguageCode ?: Locale.getDefault().language).lowercase()
            val isTagalogEnv = langCode == "tl" || langCode == "fil" || langCode.startsWith("tl-") || langCode.startsWith("fil-")
            val isEnglishEnv = langCode.startsWith("en")
            val filipinoLocale = Locale.forLanguageTag("fil-PH")

            // =========================================================================
            // 1. 日本語固有文字・全角文字（漢字・ひらがな・カタカナ・全角英数字・全角記号）
            //    → 端末の言語設定に関わらず、常に日本語（Locale.JAPANESE）で正確に識別読み上げ
            // =========================================================================

            // 1-A. 漢字（CJK Unified Ideographs）
            if (isKanji(char)) {
                val kanjiDetail = SerenaFullKanjiDetailDictionary.getKanjiDetail(char.toString())
                val text = if (kanjiDetail.isNotEmpty()) {
                    "漢字の $char、$kanjiDetail"
                } else {
                    "漢字の $char"
                }
                return Pair(text, Locale.JAPANESE)
            }

            // 1-B. ひらがな（\u3041 .. \u3096）
            if (code in 0x3041..0x3096) {
                val kata = (code + 0x60).toChar()
                val desc = kanaBaseDescMap[char] ?: kanaBaseDescMap[kata] ?: "$char のひらがな"
                return Pair("ひらがなの $char（$desc）", Locale.JAPANESE)
            }

            // 1-C. 全角カタカナ（\u30A1 .. \u30FA）
            if (code in 0x30A1..0x30FA) {
                val desc = kanaBaseDescMap[char] ?: "$char のカタカナ"
                return Pair("カタカナの $char（$desc）", Locale.JAPANESE)
            }

            // 1-D. 半角カタカナ（\uFF66 .. \uFF9D）
            if (code in 0xFF66..0xFF9D) {
                return Pair("半角カタカナの $char", Locale.JAPANESE)
            }

            // 1-E. 全角アルファベット（Ａ-Ｚ: \uFF21..\uFF3A, ａ-ｚ: \uFF41..\uFF5A）
            if (code in 0xFF21..0xFF3A || code in 0xFF41..0xFF5A) {
                val isLower = code in 0xFF41..0xFF5A
                val asciiUpper = if (isLower) (code - 0xFF41 + 'A'.code).toChar() else (code - 0xFF21 + 'A'.code).toChar()
                val nato = natoPhoneticMap[asciiUpper] ?: asciiUpper.toString()
                val prefix = if (isLower) "全角小文字 " else "全角大文字 "
                return Pair("$prefix$asciiUpper、$nato", Locale.JAPANESE)
            }

            // 1-F. 全角数字（０-９: \uFF10..\uFF19）
            if (code in 0xFF10..0xFF19) {
                val digitReading = getJapaneseDigitReading(char)
                return Pair("全角数字の $char（$digitReading）", Locale.JAPANESE)
            }

            // =========================================================================
            // 2. 半角アルファベット (A-Z, a-z) ＆ フィリピン文字 (Ñ/ñ, ₱)
            //    → 言語（タガログ語 / 英語 / 日本語）に合わせてフォネティックとTTSを切り替え
            // =========================================================================
            if (code in 'A'.code..'Z'.code || code in 'a'.code..'z'.code) {
                val isLower = char.isLowerCase()
                val upperChar = char.uppercaseChar()

                if (isTagalogEnv) {
                    val tgWord = tagalogNativeWordMap[upperChar] ?: upperChar.toString()
                    val bsWord = bisayaNativeWordMap[upperChar] ?: ""
                    val casePrefix = if (isLower) "Maliit na titik" else "Malaking titik"
                    val bsSuffix = if (bsWord.isNotEmpty() && bsWord != tgWord) ", sa Bisaya $bsWord" else ""
                    return Pair("$casePrefix $upperChar, $tgWord$bsSuffix", filipinoLocale)
                }

                if (isEnglishEnv) {
                    val natoEn = natoEnglishMap[upperChar] ?: upperChar.toString()
                    val casePrefix = if (isLower) "Lowercase" else "Capital"
                    return Pair("$casePrefix $upperChar, $natoEn", Locale.US)
                }

                // 日本語環境（半角英字: 半角であることを識別しつつ NATO + タガログ + ビサヤ）
                val nato = natoPhoneticMap[upperChar] ?: upperChar.toString()
                val tagalog = tagalogPhoneticMap[upperChar] ?: ""
                val bisaya = bisayaPhoneticMap[upperChar] ?: ""
                val prefix = if (isLower) "半角小文字 " else "半角大文字 "
                val tagalogBisayaDesc = if (tagalog.isNotEmpty() || bisaya.isNotEmpty()) {
                    "（タガログ: $tagalog、ビサヤ: $bisaya）"
                } else ""
                return Pair("$prefix$upperChar、$nato $tagalogBisayaDesc", Locale.JAPANESE)
            }

            if (char == 'Ñ' || char == 'ñ') {
                val isLower = char == 'ñ'
                return when {
                    isTagalogEnv -> {
                        val prefix = if (isLower) "Maliit na titik enye" else "Malaking titik Enye"
                        Pair("$prefix, Niños", filipinoLocale)
                    }
                    isEnglishEnv -> {
                        val prefix = if (isLower) "Lowercase enye" else "Capital Enye"
                        Pair("$prefix, N with tilde", Locale.US)
                    }
                    else -> {
                        val prefix = if (isLower) "小文字 エニェ" else "大文字 エニェ"
                        Pair("$prefix (Eñe / NiñosのÑ)", Locale.JAPANESE)
                    }
                }
            }

            if (char == '₱') {
                return when {
                    isTagalogEnv -> Pair("Simbolo ng Piso", filipinoLocale)
                    isEnglishEnv -> Pair("Philippine Peso sign", Locale.US)
                    else -> Pair("記号 フィリピン・ペソ", Locale.JAPANESE)
                }
            }

            // =========================================================================
            // 3. 半角数字 (0-9)
            // =========================================================================
            if (code in '0'.code..'9'.code) {
                if (isTagalogEnv) {
                    val tgDigit = tagalogDigitMap[char] ?: char.toString()
                    return Pair("Bilang $char, $tgDigit", filipinoLocale)
                }
                if (isEnglishEnv) {
                    return Pair("Number $char", Locale.US)
                }
                val digitReading = getJapaneseDigitReading(char)
                return Pair("半角数字の $char（$digitReading）", Locale.JAPANESE)
            }

            // =========================================================================
            // 4. 記号・空白・改行（全角記号は日本語、半角記号は言語別）
            // =========================================================================
            // 全角日本語記号は常に日本語TTS
            when (char) {
                '＠' -> return Pair("全角記号 アットマーク", Locale.JAPANESE)
                '＃' -> return Pair("全角記号 シャープ", Locale.JAPANESE)
                '＄' -> return Pair("全角記号 ドル", Locale.JAPANESE)
                '％' -> return Pair("全角記号 パーセント", Locale.JAPANESE)
                '＆' -> return Pair("全角記号 アンド", Locale.JAPANESE)
                '＊' -> return Pair("全角記号 アスタリスク", Locale.JAPANESE)
                '＋' -> return Pair("全角記号 プラス", Locale.JAPANESE)
                'ー', '―', '‐' -> return Pair("長音またはハイフン", Locale.JAPANESE)
                '？' -> return Pair("全角クエスチョンマーク", Locale.JAPANESE)
                '！' -> return Pair("全角ビックリマーク", Locale.JAPANESE)
                '　' -> return Pair("全角スペース", Locale.JAPANESE)
                '。' -> return Pair("句点（まる）", Locale.JAPANESE)
                '、' -> return Pair("読点（てん）", Locale.JAPANESE)
                '「', '」' -> return Pair("かぎかっこ", Locale.JAPANESE)
                '（', '）' -> return Pair("全角まるかっこ", Locale.JAPANESE)
                '『', '』' -> return Pair("二重かぎかっこ", Locale.JAPANESE)
                '【', '】' -> return Pair("すみつきかっこ", Locale.JAPANESE)
                '・' -> return Pair("中黒（なかぐろ）", Locale.JAPANESE)
                '…' -> return Pair("三点リーダー", Locale.JAPANESE)
                '～' -> return Pair("波ダッシュ（から）", Locale.JAPANESE)
            }

            // 半角記号は言語環境に応じて切り替え
            if (isTagalogEnv) {
                val tgSym = when (char) {
                    '.' -> "Tuldok"
                    ',' -> "Kuwit"
                    '?' -> "Tandang pananong"
                    '!' -> "Tandang padamdam"
                    '@' -> "At sign"
                    '#' -> "Hashtag"
                    '$' -> "Simbolo ng Dolyar"
                    '%' -> "Porsiyento"
                    '&' -> "At"
                    '*' -> "Asterisko"
                    '+' -> "Plus"
                    '-' -> "Gitling"
                    '=' -> "Katumbas"
                    '/' -> "Slash"
                    '\\' -> "Backslash"
                    '(' , ')' -> "Panaklong"
                    ' ' -> "Espasyo"
                    '\n' -> "Bagong linya"
                    else -> char.toString()
                }
                return Pair(tgSym, filipinoLocale)
            }

            if (isEnglishEnv) {
                val enSym = when (char) {
                    '.' -> "Period"
                    ',' -> "Comma"
                    '?' -> "Question mark"
                    '!' -> "Exclamation mark"
                    '@' -> "At sign"
                    '#' -> "Hash"
                    '$' -> "Dollar sign"
                    '%' -> "Percent"
                    '&' -> "Ampersand"
                    '*' -> "Asterisk"
                    '+' -> "Plus"
                    '-' -> "Hyphen"
                    '=' -> "Equals"
                    '/' -> "Slash"
                    '\\' -> "Backslash"
                    '(', ')' -> "Parenthesis"
                    ' ' -> "Space"
                    '\n' -> "New line"
                    else -> char.toString()
                }
                return Pair(enSym, Locale.US)
            }

            val jaSym = when (char) {
                '.' -> "半角ピリオド"
                ',' -> "半角カンマ"
                '@' -> "半角記号 アットマーク"
                '#' -> "半角記号 シャープ"
                '$' -> "半角記号 ドル"
                '%' -> "半角記号 パーセント"
                '&' -> "半角記号 アンド"
                '*' -> "半角記号 アスタリスク"
                '+' -> "半角記号 プラス"
                '-' -> "半角ハイフン"
                '=' -> "記号 イコール"
                '/' -> "記号 スラッシュ"
                '\\' -> "記号 バックスラッシュ"
                '?' -> "半角クエスチョンマーク"
                '!' -> "半角ビックリマーク"
                '(', ')' -> "半角まるかっこ"
                ' ' -> "半角スペース"
                '\n' -> "改行"
                '~' -> "半角チルダ"
                else -> char.toString()
            }
            Pair(jaSym, Locale.JAPANESE)
        } catch (e: Exception) {
            Pair(char.toString(), Locale.getDefault())
        }
    }

    private fun getJapaneseDigitReading(char: Char): String {
        return when (char) {
            '0', '０' -> "ゼロ"
            '1', '１' -> "イチ"
            '2', '２' -> "ニ"
            '3', '３' -> "サン"
            '4', '４' -> "ヨン"
            '5', '５' -> "ゴ"
            '6', '６' -> "ロク"
            '7', '７' -> "ナナ"
            '8', '８' -> "ハチ"
            '9', '９' -> "キュウ"
            else -> char.toString()
        }
    }

    /**
     * 漢字判定（CJK統合漢字、拡張漢字、CJK互換漢字など）
     */
    private fun isKanji(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FFF) ||      // CJK Unified Ideographs (常用・主要漢字)
               (code in 0x3400..0x4DBF) ||      // CJK Unified Ideographs Extension A
               (code in 0xF900..0xFAFF) ||      // CJK Compatibility Ideographs
               (code in 0x20000..0x2A6DF) ||    // CJK Unified Ideographs Extension B
               Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN
    }
}
