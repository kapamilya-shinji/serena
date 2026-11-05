package com.kapamilya.serena.speech

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.kapamilya.serena.translation.SerenaLocalizationBridge
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * KapamilyaVoiceBridge (100% Pure Android Standard TTS & Open JTalk Edition — Zero Microsoft Voice Dependency)
 *
 * 1. Multi-Engine Speech Synthesis Switching (100% Legal on Android):
 *    - User-Specified System Default TTS (`system` — Default)
 *    - Open JTalk Engine (`open_jtalk` — Default voice: `tohoku-f01-neutral` latest Tohoku University model, switchable to `mei_normal`, etc.)
 *    - Google Speech Services (`google` - `com.google.android.tts` for native Android `fil-PH` Tagalog, Japanese, and 7-Dialect English)
 *    - eSpeak-NG Android (`espeak` / `espeak_en` - `com.reecedunn.espeak`)
 *    - Samsung TTS (`samsung` - `com.samsung.SMT`)
 * 2. Unicode Multilingual Auto-Router & JIS X 0208 (EUC-JP) Chinese/Japanese Coexistence:
 *    - Guarantees 0% false positives on Japanese Kanji & numbers (`5683`, `1個`, `2時間`, etc.)
 *    - Automatically detects Tagalog (`fil-PH`), Simplified/Traditional Chinese (`zh-CN` / `zh-TW`), Korean Hangul (`ko-KR`),
 *      Russian Cyrillic (`ru-RU`), Arabic (`ar-SA`), Hindi Devanagari (`hi-IN`), Thai (`th-TH`),
 *      Hebrew (`he-IL`), Greek (`el-GR`), Vietnamese (`vi-VN`), Spanish (`es-ES`), and English (`en`).
 * 3. 7-Dialect Regional English & Taglish Pronunciation Engine:
 *    - Australian (`en-AU`), Singaporean (`en-SG`), Irish (`en-IE`), New Zealand (`en-NZ`),
 *      British (`en-GB`), American (`en-US`), and Philippine Taglish (`fil-PH`).
 *
 * Author: Shinji Sakiyama <shinjisakiyama@gmail.com>
 * Three Core Pillars: Bayanihan • Magna Carta • Kapamilya
 */
enum class KapamilyaEngineMode(val id: String, val labelJa: String, val enginePackage: String?) {
    SYSTEM_DEFAULT("system", "ユーザー指定の標準TTS（デフォルト） ＋ 多言語自動切替", null),
    OPEN_JTALK("open_jtalk", "Open JTalk エンジン（デフォルト: 東北大学最新モデル tohoku-f01-neutral / mei_normal 切替可）", null),
    PIPER_FEMALE("piper_female", "Piper 多言語女性音声エンジン（タガログ語・日本語・英語・全13言語 女性音専用）", null),
    GOOGLE("google", "Google 音声サービス (多言語強制切替・女性音優先)", "com.google.android.tts"),
    ESPEAK_EN("espeak_en", "ユーザー指定の標準TTS (日本語) ＋ eSpeak-NG (英語・中国語・多言語共存)", "com.reecedunn.espeak"),
    ESPEAK("espeak", "eSpeak-NG (日本語＋中国語＋英語・多言語共存)", "com.reecedunn.espeak"),
    SAMSUNG("samsung", "Samsung TTS (日本語 ＋ 英語・多言語)", "com.samsung.SMT");

    companion object {
        fun fromId(id: String?): KapamilyaEngineMode {
            if (id.isNullOrBlank()) return SYSTEM_DEFAULT
            return entries.find { it.id.equals(id.trim(), ignoreCase = true) } ?: SYSTEM_DEFAULT
        }
    }
}

enum class KapamilyaUnicodeRouterMode(val id: String, val labelJa: String) {
    AUTO_LOCALE("auto_locale", "自動言語切替 (多言語が来たら Google 多言語TTS または Piper 女性音声へ強制自動切替)"),
    PIPER_FEMALE("piper_female", "Piper 多言語女性音声 (対応するすべての言語へ女性音のみで自動切替)"),
    GOOGLE("google", "Google 音声サービス (多言語が来たら強制的に Google 多言語TTS 女性音へ切替)"),
    ESPEAK("espeak", "eSpeak-NG (Unicode言語判定で自動eSpeak発音: 中国語・韓国語・ロシア語・アラビア語等)"),
    OFF("off", "オフ (メイン音声エンジン設定に従う)");

    companion object {
        fun fromId(id: String?): KapamilyaUnicodeRouterMode {
            if (id.isNullOrBlank()) return AUTO_LOCALE
            return entries.find { it.id.equals(id.trim(), ignoreCase = true) } ?: AUTO_LOCALE
        }
    }
}

enum class KapamilyaDialectMode(
    val id: String,
    val labelJa: String,
    val locale: Locale
) {
    EN_AU("en-AU", "オーストラリア英語 (en-AU)", Locale("en", "AU")),
    EN_SG("en-SG", "シンガポール英語 (en-SG)", Locale("en", "SG")),
    EN_IE("en-IE", "アイルランド英語 (en-IE)", Locale("en", "IE")),
    EN_NZ("en-NZ", "ニュージーランド英語 (en-NZ)", Locale("en", "NZ")),
    EN_GB("en-GB", "イギリス英語 (en-GB)", Locale.UK),
    EN_US("en-US", "アメリカ英語 (en-US)", Locale.US),
    TL_PH("tl-PH", "フィリピン・タグリッシュ (fil-PH)", Locale.forLanguageTag("fil-PH"));

    companion object {
        fun fromId(id: String?): KapamilyaDialectMode {
            if (id.isNullOrBlank()) return EN_AU
            return entries.find { it.id.equals(id.trim(), ignoreCase = true) } ?: EN_AU
        }
    }
}

data class MultilingualSegment(
    val langCode: String,
    val text: String
)

class KapamilyaVoiceBridge(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "KapamilyaVoiceBridge"
        const val KEY_ENGINE_MODE = "kapamilya_engine_mode"
        const val KEY_UNICODE_ROUTER = "kapamilya_unicode_router"
        const val KEY_ENGLISH_DIALECT = "kapamilya_english_dialect"

        private val CHINESE_GRAMMAR_CHARS: Set<Char> = "你您她它咱吗嗎".toSet()

        private val VIETNAMESE_CHARS: Set<Char> = (
            "ăâđêôơưĂÂĐÊÔƠƯ" +
            "àảãáạằẳẵắặầẩẫấậèẻẽéẹềểễếệìỉĩíịòỏõóọồổỗốộờởỡớợùủũúụừửữứựỳỷỹýỵ" +
            "ÀẢÃÁẠẰẲẴẮẶẦẨẪẤẬÈẺẼÉẸỀỂỄẾỆÌỈĨÍỊÒỎÕÓỌỒỔỖỐỘỜỞỠỚỢÙỦŨÚỤỪỬỮỨỰỲỶỸÝỴ"
        ).toSet()

        private val CORE_TAGLISH_PHONETICS: Map<String, Pair<String, String>> = mapOf(
            "kapamilya" to Pair("カパミリャ", "Kah-pah-meel-yah"),
            "bayanihan" to Pair("バヤニハン", "Bah-yah-nee-hahn"),
            "mabuhay" to Pair("マブーハイ", "Mah-boo-high"),
            "salamat" to Pair("サラマット", "Sah-lah-maht"),
            "maraming" to Pair("マラミング", "Mah-rah-ming"),
            "pamilya" to Pair("パミリャ", "Pah-meel-yah"),
            "tahanan" to Pair("タハナン", "Tah-hah-nahn"),
            "mahal" to Pair("マハル", "Mah-hahl"),
            "kumusta" to Pair("クムスタ", "Koo-moos-tah"),
            "kamusta" to Pair("カムスタ", "Kah-moos-tah"),
            "magandang" to Pair("マガンダン", "Mah-gahn-dahng"),
            "paalam" to Pair("パアラム", "Pah-ah-lahm"),
            "pilipinas" to Pair("ピリピナス", "Pee-lee-pee-nahs"),
            "taglish" to Pair("タグリッシュ", "Tag-lish"),
            "pagbabalik" to Pair("パグババリック", "Pahg-bah-bah-leek"),
            "na-unlock" to Pair("ナアンロック", "Nah-unlock"),
            "bantay" to Pair("バンタイ", "Bahn-tay"),
            "magpahinga" to Pair("マグパヒンガ", "Mahg-pah-heen-gah")
        )

        private val TAGALOG_SENTENCE_MARKERS: List<String> = listOf(
            "maligayang pagdating",
            "narito na ang iyong",
            "pamilya tayo",
            "mahal ka namin",
            "ingat lagi",
            "hanggang sa muli",
            "aking kapamilya",
            "maraming salamat",
            "mahal kita",
            "mag-ingat ka palagi",
            "magandang araw",
            "magandang umaga",
            "magandang gabi",
            "naka-lock na ang screen",
            "na-unlock na ang screen",
            "maligayang pagbabalik",
            "nandito lang ako",
            "magpahinga ka muna",
            "mata ng kapamilya",
            "live tagapagsalaysay",
            "tagasuri ng paninda",
            "tagabasa ng dokumento at talaan",
            "kapamilya bayanihan link",
            "tulay ng telepono at pc",
            "bayanihan gabay",
            "tinig ng serena",
            "kapamilya kausap",
            "bukas na ang mata ng kapamilya",
            "naka-hinto na ang",
            "narito na ang mata ng kapamilya",
            "bukas na ang bayanihan gabay",
            "bukas na ang kapamilya bayanihan link",
            "natanggap na mula sa kapamilya",
            "naipadala na sa kapamilya",
            "ilalarawan ko na ang buong video",
            "susuriin ko na ang larawan ng produkto",
            "aayusin ko ang pdf",
            "magkakaugnay na ang iyong telepono at pc"
        )

        // Comprehensive Tier-1 Unambiguous Tagalog Vocabulary (3+ chars, never normal English words; 1 hit = Tagalog)
        private val TAGALOG_TIER1_WORDS: Set<String> = setOf(
            // Grammar markers, particles, linkers & enclitics
            "ang", "mga", "nang", "opo", "oho", "daw", "raw", "din", "rin", "lang", "lamang",
            "muna", "naman", "kasi", "kaya", "tuloy", "sana", "yata", "pala", "nga", "nina", "kina",
            // Personal, possessive & demonstrative pronouns
            "ako", "ikaw", "siya", "kami", "tayo", "kayo", "sila", "niya", "namin", "natin",
            "ninyo", "nila", "akin", "aking", "iyo", "iyong", "kanya", "kanyang", "kaniya", "kaniyang",
            "amin", "aming", "atin", "ating", "inyo", "inyong", "kanila", "kanilang", "kata", "kita",
            "ito", "itong", "iyan", "iyang", "iyon", "dito", "diyan", "doon", "rito", "riyan", "roon",
            "ganito", "ganyan", "ganoon", "nito", "niyan", "noon", "heto", "hayan", "hayun",
            // Interrogatives, conjunctions, modals & negation
            "ano", "anong", "sino", "sinong", "saan", "saang", "nasaan", "nasa", "kailan", "bakit",
            "paano", "paanong", "ilan", "ilang", "alin", "aling", "kanino", "kaninong", "kumusta", "kamusta",
            "magkano", "pero", "kaso", "habang", "kapag", "kung", "dahil", "upang", "kahit", "maski",
            "bago", "bagong", "pagkatapos", "sakali", "subalit", "ngunit", "datapwat", "samantala",
            "hindi", "huwag", "wala", "walang", "meron", "mayroon", "merong", "mayroong",
            "puwede", "pwede", "puwedeng", "pwedeng", "maaari", "maaaring", "kailangan", "kailangang",
            "dapat", "gusto", "gustong", "ibig", "nais", "ayaw", "alam", "marahil", "siguro",
            "talaga", "talagang", "totoo", "totoong", "syempre", "siyempre", "mismo", "halos",
            "lahat", "bawat", "iba", "ibang", "kapwa", "sarili", "sariling", "tulad", "parang",
            "kaysa", "mula", "hanggang", "tungkol", "laban", "ayon", "bukod",
            // Everyday verbs & inflections
            "kumain", "kain", "kakain", "kumakain", "kinain", "pagkain", "inom", "uminom", "iinom",
            "tulog", "matulog", "natulog", "matutulog", "gising", "gumising", "lakad", "maglakad", "naglakad",
            "takbo", "tumakbo", "sakay", "sumakay", "baba", "bumaba", "pasok", "pumasok", "labas", "lumabas",
            "uwi", "umuwi", "uuwi", "alis", "umalis", "aalis", "dating", "dumating", "pagdating",
            "balik", "bumalik", "pagbabalik", "tingin", "tumingin", "kinig", "makinig", "rinig", "narinig",
            "sabi", "sinabi", "nagsabi", "sabihin", "usap", "kausap", "tanong", "magtanong", "tinanong",
            "sagot", "sumagot", "bigay", "ibigay", "nagbigay", "kuha", "kumuha", "kinuha", "bili", "bumili",
            "bayad", "magbayad", "gawa", "gumawa", "ginawa", "trabaho", "magtrabaho", "aral", "nag-aaral",
            "basa", "magbasa", "tagabasa", "sulat", "magsulat", "susulatan", "tulong", "tumulong",
            "pupunta", "pumunta", "punta", "gagawin", "ginagawa", "nakita", "makita",
            // Everyday nouns, greetings, time & adjectives
            "mahal", "minamahal", "salamat", "maraming", "mabuhay", "ingat", "mag-ingat",
            "umaga", "tanghali", "hapon", "gabi", "araw", "buwan", "taon", "oras", "ngayon", "ngayong",
            "bukas", "kahapon", "mamaya", "kanina", "lagi", "palagi", "minsan", "madalas",
            "bahay", "tahanan", "pamilya", "kapamilya", "bayanihan", "kaibigan", "kapatid", "magulang",
            "nanay", "tatay", "anak", "asawa", "tao", "taong", "bata", "batang", "matanda", "babae", "lalaki",
            "tubig", "kanin", "ulam", "pera", "buhay", "puso", "isip", "mata", "kamay", "paa", "ulo", "mukha",
            "boses", "tinig", "salita", "wika", "bansa", "pilipinas", "pilipino", "tagalog", "taglish",
            "maganda", "magandang", "mabuti", "mabuting", "masama", "masamang", "malaki", "malaking",
            "maliit", "marami", "kaunti", "mabilis", "mabagal", "mainit", "malamig", "masaya", "malungkot",
            "pagod", "gutom", "uhaw", "luma", "tama", "mali", "isa", "isang", "dalawa", "dalawang",
            "tatlo", "tatlong", "apat", "lima", "limang", "anim", "pito", "walo", "siyam", "sampu",
            "unang", "pangalawa", "ikatlo", "huli", "huling", "gitna", "taas", "ibaba", "kaliwa", "kanan",
            "harap", "likod", "loob", "gitnang", "maligayang", "narito", "nandito", "naka-lock", "na-unlock",
            "magpahinga", "bantay", "dangal", "tagapagsalaysay", "tagasuri", "paninda", "dokumento",
            "talaan", "tulay", "telepono", "gabay", "naka-hinto", "ilalarawan", "susuriin", "aayusin",
            "patlang", "magkakaugnay", "natanggap", "naipadala", "larawan", "produkto", "buong", "wakas",
            "simula", "mensahe", "paksa", "petsa", "paalala", "babala", "tala", "pangalan", "tirahan",
            "halaga", "kabuuang", "sukli", "resibo", "tindahan", "kusina", "kwarto", "banyo", "bintana", "pinto"
        )

        // Tier-2 2-letter Tagalog enclitics/particles (2+ hits in a clause or 1 hit alongside Tier-1 = Tagalog)
        private val TAGALOG_TIER2_PARTICLES: Set<String> = setOf(
            "ng", "sa", "na", "pa", "ba", "po", "ho", "ka", "ko", "mo", "ni", "ay", "oo"
        )

        // Core English vocabulary for sub-clause English vs Tagalog boundary detection
        private val ENGLISH_CORE_WORDS: Set<String> = setOf(
            "the", "and", "for", "that", "with", "this", "from", "have", "has", "had", "are", "was", "were",
            "will", "would", "could", "should", "what", "when", "where", "which", "who", "whom", "whose",
            "how", "why", "your", "you", "they", "their", "them", "there", "here", "about", "into", "over",
            "after", "before", "between", "under", "again", "further", "then", "once", "hello", "welcome",
            "thank", "thanks", "please", "download", "release", "official", "website", "click", "button",
            "window", "message", "subject", "today", "tomorrow", "yesterday", "morning", "afternoon",
            "evening", "night", "friend", "friends", "world", "system", "setting", "settings", "screen",
            "reader", "voice", "email", "mail", "folder", "file", "document", "search", "next", "previous",
            "open", "close", "save", "cancel", "delete", "send", "reply", "forward", "inbox", "update"
        )

        // Tagalog verbal/adjectival prefix & hyphen morphology
        private val TAGALOG_MORPHOLOGY_RE = Regex(
            "\\b(?:(?:nag|mag|na|ma|pa|ka|taga|pinaka|ika|pag|ipag|maki|naki|paki)-[a-z]{2,}" +
            "|(?:nakaka|makaka|pinaka|pagka|pakiki|nakiki|makiki|ikapag|ipagpa|nakapag|makapag|nagpa|magpa|nagka|magka|tagapag)[a-z]{3,})\\b",
            RegexOption.IGNORE_CASE
        )

        private val LATIN_WORD_RE = Regex("[A-Za-z\\u00C0-\\u024F']+(?:-[A-Za-z\\u00C0-\\u024F']+)?")

        private val CAMEL_LOWER_TO_UPPER_RE = Regex("([a-z])([A-Z])")
        private val CAMEL_ACRONYM_TO_WORD_RE = Regex("([A-Z]{2,})([A-Z][a-z])")
        private val SNAKE_CASE_RE = Regex("([A-Za-z0-9])_([A-Za-z0-9])")

        private val SHORTCUT_RE = Regex(
            "^(?:(?:Alt|Ctrl|Control|Shift|Win|NVDA|Insert|CapsLock)\\s*\\+\\s*)+[A-Za-z0-9\\+\\-\\s]+$",
            RegexOption.IGNORE_CASE
        )

        private val TOKEN_SPLIT_RE = Regex(
            "([A-Za-z\\u00C0-\\u024F][A-Za-z0-9\\u00C0-\\u024F'\\-\\+\\*&_=@#\\s]*[A-Za-z0-9\\u00C0-\\u024F]" +
            "|[A-Za-z\\u00C0-\\u024F]+" +
            "|[\\uAC00-\\uD7AF\\u1100-\\u11FF\\u3130-\\u318F]+" +
            "|[\\u0400-\\u052F]+" +
            "|[\\u0600-\\u06FF\\u0750-\\u077F]+" +
            "|[\\u0900-\\u097F]+" +
            "|[\\u0E00-\\u0E7F]+" +
            "|[\\u0590-\\u05FF]+" +
            "|[\\u0370-\\u03FF]+" +
            "|[、。，！？；：,\\.\\!\\?\\:;\\(\\)/\\[\\]\\n]+)"
        )

        private val eucJpCharset: Charset? = try {
            Charset.forName("EUC-JP")
        } catch (_: Exception) {
            try {
                Charset.forName("Shift_JIS")
            } catch (_: Exception) {
                null
            }
        }

        fun isNonJapaneseChineseChar(ch: Char): Boolean {
            if (ch in CHINESE_GRAMMAR_CHARS) return true
            val cp = ch.code
            if (!((cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF))) return false
            val charset = eucJpCharset ?: return false
            return try {
                !charset.newEncoder().canEncode(ch)
            } catch (_: Exception) {
                false
            }
        }

        fun hasJapaneseKana(text: String): Boolean {
            for (ch in text) {
                val cp = ch.code
                if ((cp in 0x3040..0x30FF) || (cp in 0xFF66..0xFF9F)) return true
            }
            return false
        }

        fun splitCamelCaseIdentifiers(text: String): String {
            if (text.isEmpty()) return text
            var s = CAMEL_LOWER_TO_UPPER_RE.replace(text, "$1 $2")
            s = CAMEL_ACRONYM_TO_WORD_RE.replace(s, "$1 $2")
            s = SNAKE_CASE_RE.replace(s, "$1 $2")
            return s
        }

        fun isTagalogLatinText(text: String): Boolean {
            val lower = text.lowercase(Locale.ROOT)
            if (TAGALOG_SENTENCE_MARKERS.any { lower.contains(it) }) return true
            if (TAGALOG_MORPHOLOGY_RE.containsMatchIn(lower)) return true

            var score = 0
            for (m in LATIN_WORD_RE.findAll(text)) {
                val rawWord = m.value
                val w = rawWord.lowercase(Locale.ROOT)
                if (w in TAGALOG_TIER1_WORDS) {
                    score += 2
                    if (score >= 2) return true
                } else if (w in TAGALOG_TIER2_PARTICLES) {
                    val isAllCapsAbbrev = rawWord.length == 2 && rawWord[0].isUpperCase() && rawWord[1].isUpperCase() && w != "ng"
                    if (!isAllCapsAbbrev) {
                        score += 1
                        if (score >= 2) return true
                    }
                }
            }
            return score >= 2
        }

        private fun isPureLatinClause(s: String): Boolean {
            var hasLatin = false
            for (ch in s) {
                val cp = ch.code
                if ((cp in 0x3040..0x9FFF) || (cp in 0x3400..0x4DBF) || (cp in 0xAC00..0xD7AF) || (cp in 0x0400..0x0E7F)) {
                    return false
                }
                if ((cp in 0x0041..0x005A) || (cp in 0x0061..0x007A) || (cp in 0x00C0..0x024F)) {
                    hasLatin = true
                }
            }
            return hasLatin && s.none { it in VIETNAMESE_CHARS }
        }

        private fun splitLatinClauseByEnAndTagalog(clause: String): List<MultilingualSegment> {
            val words = LATIN_WORD_RE.findAll(clause).toList()
            if (words.size < 2) {
                val lang = detectSegmentLanguage(clause, hintLang = "en")
                return listOf(MultilingualSegment(lang, clause))
            }

            val wordTags = IntArray(words.size)
            var hasTl = false
            var hasEn = false
            for (i in words.indices) {
                val raw = words[i].value
                val w = raw.lowercase(Locale.ROOT)
                val isAllCaps2 = raw.length == 2 && raw[0].isUpperCase() && raw[1].isUpperCase() && w != "ng"
                when {
                    w in TAGALOG_TIER1_WORDS || TAGALOG_MORPHOLOGY_RE.matches(w) -> {
                        wordTags[i] = 1
                        hasTl = true
                    }
                    w in TAGALOG_TIER2_PARTICLES && !isAllCaps2 -> {
                        wordTags[i] = 1
                        hasTl = true
                    }
                    w in ENGLISH_CORE_WORDS -> {
                        wordTags[i] = -1
                        hasEn = true
                    }
                    else -> wordTags[i] = 0
                }
            }

            if (!hasTl || !hasEn) {
                val lang = if (isTagalogLatinText(clause)) "fil-ph" else if (clause.any { it in "ñÑ¿¡" }) "es" else "en"
                return listOf(MultilingualSegment(lang, clause))
            }

            val resolvedLang = Array(words.size) { "" }
            var currentLang = ""
            for (i in words.indices) {
                if (wordTags[i] == 1) currentLang = "fil-ph"
                else if (wordTags[i] == -1) currentLang = "en"
                resolvedLang[i] = currentLang
            }
            currentLang = ""
            for (i in words.indices.reversed()) {
                if (wordTags[i] == 1) currentLang = "fil-ph"
                else if (wordTags[i] == -1) currentLang = "en"
                if (resolvedLang[i].isEmpty()) resolvedLang[i] = currentLang
            }
            for (i in words.indices) {
                if (resolvedLang[i].isEmpty()) resolvedLang[i] = "fil-ph"
            }

            val subSegments = mutableListOf<MultilingualSegment>()
            var segStartChar = 0
            var activeLang = resolvedLang[0]
            for (i in 1 until words.size) {
                if (resolvedLang[i] != activeLang) {
                    val splitPos = words[i].range.first
                    val chunk = clause.substring(segStartChar, splitPos).trim()
                    if (chunk.isNotEmpty()) {
                        subSegments.add(MultilingualSegment(activeLang, chunk))
                    }
                    segStartChar = splitPos
                    activeLang = resolvedLang[i]
                }
            }
            val tail = clause.substring(segStartChar).trim()
            if (tail.isNotEmpty()) {
                subSegments.add(MultilingualSegment(activeLang, tail))
            }
            return subSegments
        }

        fun detectSegmentLanguage(segText: String, hintLang: String = "ja"): String {
            val trimmed = segText.trim()
            if (trimmed.isEmpty()) return "ja"

            val hl = hintLang.lowercase(Locale.ROOT).replace('_', '-')
            if (hl.startsWith("zh")) {
                return if ("tw" in hl || "hk" in hl || "hant" in hl) "zh-tw" else "zh-cn"
            }
            if (hl.startsWith("fil") || hl.startsWith("tl")) {
                return "fil-ph"
            }

            var hasKana = false
            var hasHangul = false
            var hasCyrillic = false
            var hasArabic = false
            var hasDevanagari = false
            var hasThai = false
            var hasHebrew = false
            var hasGreek = false
            var hasViet = false
            var hasLatin = false
            var cjkCount = 0
            var zhMarkerCount = 0

            for (ch in trimmed) {
                val cp = ch.code
                when {
                    (cp in 0x3040..0x30FF) || (cp in 0xFF66..0xFF9F) -> hasKana = true
                    (cp in 0xAC00..0xD7AF) || (cp in 0x1100..0x11FF) || (cp in 0x3130..0x318F) -> hasHangul = true
                    cp in 0x0400..0x052F -> hasCyrillic = true
                    (cp in 0x0600..0x06FF) || (cp in 0x0750..0x077F) -> hasArabic = true
                    cp in 0x0900..0x097F -> hasDevanagari = true
                    cp in 0x0E00..0x0E7F -> hasThai = true
                    cp in 0x0590..0x05FF -> hasHebrew = true
                    cp in 0x0370..0x03FF -> hasGreek = true
                    ch in VIETNAMESE_CHARS -> hasViet = true
                    (cp in 0x0041..0x005A) || (cp in 0x0061..0x007A) || (cp in 0x00C0..0x024F) -> hasLatin = true
                    (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) -> {
                        cjkCount++
                        if (isNonJapaneseChineseChar(ch)) {
                            zhMarkerCount++
                        }
                    }
                }
            }

            if (hasHangul) return "ko"
            if (hasCyrillic) return "ru"
            if (hasArabic) return "ar"
            if (hasDevanagari) return "hi"
            if (hasThai) return "th"
            if (hasHebrew) return "he"
            if (hasGreek) return "el"
            if (hasViet) return "vi"
            if (hasKana) return "ja"
            if (cjkCount > 0) {
                return if (zhMarkerCount >= 1) "zh-cn" else "ja"
            }
            if (hasLatin) {
                if (isTagalogLatinText(trimmed)) return "fil-ph"
                if (trimmed.any { it in "ñÑ¿¡" }) return "es"
                return "en"
            }
            return "ja"
        }

        private fun hasAlnumOrCjk(s: String): Boolean {
            for (ch in s) {
                val cp = ch.code
                if (ch.isLetterOrDigit() ||
                    (cp in 0x3040..0x9FFF) ||
                    (cp in 0x3400..0x4DBF) ||
                    (cp in 0xAC00..0xD7AF)
                ) {
                    return true
                }
            }
            return false
        }

        fun splitMultilingualSegments(text: String, hintLang: String = "ja"): List<MultilingualSegment> {
            if (text.isBlank()) return emptyList()
            val utteranceHasKana = hasJapaneseKana(text)

            val parts = mutableListOf<String>()
            var lastIdx = 0
            for (match in TOKEN_SPLIT_RE.findAll(text)) {
                if (match.range.first > lastIdx) {
                    parts.add(text.substring(lastIdx, match.range.first))
                }
                parts.add(match.value)
                lastIdx = match.range.last + 1
            }
            if (lastIdx < text.length) {
                parts.add(text.substring(lastIdx))
            }

            val results = mutableListOf<MultilingualSegment>()
            for (part in parts) {
                val s = part.trim()
                if (s.isEmpty()) continue
                if (!hasAlnumOrCjk(s)) {
                    if (results.isNotEmpty()) {
                        val prev = results.last()
                        results[results.lastIndex] = prev.copy(text = prev.text + s)
                    }
                    continue
                }

                val subParts: List<MultilingualSegment> = if (isPureLatinClause(s) && !(utteranceHasKana && SHORTCUT_RE.matches(s))) {
                    splitLatinClauseByEnAndTagalog(s)
                } else {
                    var langCode = detectSegmentLanguage(s, hintLang = hintLang)
                    if (utteranceHasKana && langCode == "en" && (SHORTCUT_RE.matches(s) || (s.length == 1 && s[0].isLetter()))) {
                        langCode = "ja"
                    }
                    listOf(MultilingualSegment(langCode, s))
                }

                for (sub in subParts) {
                    val langCode = sub.langCode
                    val subText = sub.text
                    if (results.isNotEmpty() && results.last().langCode == langCode) {
                        val prev = results.last()
                        val endsWithCjkPunct = prev.text.endsWith("、") || prev.text.endsWith("。") ||
                            prev.text.endsWith("，") || prev.text.endsWith("(") || prev.text.endsWith("（")
                        val sep = if (langCode == "ja" || endsWithCjkPunct) "" else " "
                        results[results.lastIndex] = prev.copy(text = prev.text + sep + subText)
                    } else {
                        results.add(MultilingualSegment(langCode, subText))
                    }
                }
            }
            return results
        }

        fun applyKapamilyaTaglishPhonetics(text: String, targetLangCode: String, dialect: KapamilyaDialectMode): String {
            val lc = targetLangCode.lowercase(Locale.ROOT)
            if (lc.startsWith("fil") || lc.startsWith("tl") || dialect == KapamilyaDialectMode.TL_PH) {
                return text
            }
            var out = text
            for ((word, readings) in CORE_TAGLISH_PHONETICS) {
                val (jaReading, enReading) = readings
                val replacement = if (lc.startsWith("ja")) jaReading else enReading
                val regex = Regex("\\b$word\\b", RegexOption.IGNORE_CASE)
                out = regex.replace(out, replacement)
            }
            return out
        }
    }

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val secondaryEngines = ConcurrentHashMap<String, TextToSpeech>()
    private val secondaryEngineReady = ConcurrentHashMap<String, Boolean>()
    private val speechGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        // Pre-warm Google Multilingual Natural TTS so Tagalog, English, Japanese, Spanish, etc. speak at highest quality immediately
        try {
            getOrInitSecondaryTts("com.google.android.tts")
        } catch (_: Exception) {}
    }

    var engineMode: KapamilyaEngineMode
        get() = KapamilyaEngineMode.fromId(prefs.getString(KEY_ENGINE_MODE, KapamilyaEngineMode.SYSTEM_DEFAULT.id))
        set(value) {
            prefs.edit().putString(KEY_ENGINE_MODE, value.id).apply()
        }

    var unicodeRouterMode: KapamilyaUnicodeRouterMode
        get() = KapamilyaUnicodeRouterMode.fromId(prefs.getString(KEY_UNICODE_ROUTER, KapamilyaUnicodeRouterMode.AUTO_LOCALE.id))
        set(value) {
            prefs.edit().putString(KEY_UNICODE_ROUTER, value.id).apply()
        }

    var englishDialectMode: KapamilyaDialectMode
        get() = KapamilyaDialectMode.fromId(prefs.getString(KEY_ENGLISH_DIALECT, KapamilyaDialectMode.EN_AU.id))
        set(value) {
            prefs.edit().putString(KEY_ENGLISH_DIALECT, value.id).apply()
        }

    fun cycleEngineMode(): KapamilyaEngineMode {
        val all = KapamilyaEngineMode.entries
        val next = all[(all.indexOf(engineMode) + 1) % all.size]
        engineMode = next
        return next
    }

    fun cycleUnicodeRouterMode(): KapamilyaUnicodeRouterMode {
        val all = KapamilyaUnicodeRouterMode.entries
        val next = all[(all.indexOf(unicodeRouterMode) + 1) % all.size]
        unicodeRouterMode = next
        return next
    }

    fun cycleEnglishDialectMode(): KapamilyaDialectMode {
        val all = KapamilyaDialectMode.entries
        val next = all[(all.indexOf(englishDialectMode) + 1) % all.size]
        englishDialectMode = next
        return next
    }

    fun resolveLocaleForSegment(segLang: String): Locale {
        return when (segLang.lowercase(Locale.ROOT)) {
            "ja" -> Locale.JAPANESE
            "en" -> englishDialectMode.locale
            "fil-ph", "fil", "tl" -> Locale.forLanguageTag("fil-PH")
            "zh-tw", "zh-hk" -> Locale.TRADITIONAL_CHINESE
            "zh-cn", "zh" -> Locale.SIMPLIFIED_CHINESE
            "ko" -> Locale.KOREAN
            "ru" -> Locale.forLanguageTag("ru-RU")
            "ar" -> Locale.forLanguageTag("ar-SA")
            "hi" -> Locale.forLanguageTag("hi-IN")
            "th" -> Locale.forLanguageTag("th-TH")
            "vi" -> Locale.forLanguageTag("vi-VN")
            "el" -> Locale.forLanguageTag("el-GR")
            "he" -> Locale.forLanguageTag("he-IL")
            "es" -> Locale.forLanguageTag("es-ES")
            "fr" -> Locale.FRENCH
            "de" -> Locale.GERMAN
            else -> englishDialectMode.locale
        }
    }

    fun stop() {
        speechGeneration.incrementAndGet()
        KapamilyaOpenJTalkSynthesizer.stopAudioTrack()
        KapamilyaPiperSynthesizer.stopAudioTrack()
        for ((_, engine) in secondaryEngines) {
            try {
                engine.stop()
            } catch (_: Exception) {}
        }
    }

    private fun isAnyTtsSpeaking(primaryTts: TextToSpeech?): Boolean {
        try {
            if (KapamilyaOpenJTalkSynthesizer.isPlaying()) return true
            if (primaryTts?.isSpeaking == true) return true
        } catch (_: Exception) {}
        for ((_, engine) in secondaryEngines) {
            try {
                if (engine.isSpeaking) return true
            } catch (_: Exception) {}
        }
        return false
    }

    fun shutdown() {
        stop()
        for ((_, engine) in secondaryEngines) {
            try {
                engine.shutdown()
            } catch (_: Exception) {}
        }
        secondaryEngines.clear()
        secondaryEngineReady.clear()
    }

    private fun getOrInitSecondaryTts(pkgName: String, onReady: ((TextToSpeech) -> Unit)? = null): TextToSpeech? {
        val existing = secondaryEngines[pkgName]
        if (existing != null && secondaryEngineReady[pkgName] == true) {
            onReady?.invoke(existing)
            return existing
        }
        if (existing == null) {
            try {
                var createdTts: TextToSpeech? = null
                createdTts = TextToSpeech(context.applicationContext, { status ->
                    if (status == TextToSpeech.SUCCESS && createdTts != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            val attrs = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                            createdTts?.setAudioAttributes(attrs)
                        }
                        secondaryEngineReady[pkgName] = true
                        createdTts?.let { onReady?.invoke(it) }
                    } else {
                        secondaryEngines.remove(pkgName)
                        try { createdTts?.shutdown() } catch (_: Exception) {}
                    }
                }, pkgName)
                secondaryEngines[pkgName] = createdTts
            } catch (e: Exception) {
                Log.w(TAG, "Secondary TTS engine $pkgName not available: ${e.message}")
            }
        }
        return null
    }

    private fun dispatchSingleSegment(
        seg: MultilingualSegment,
        segQueueMode: Int,
        primaryTts: TextToSpeech,
        activeEngine: KapamilyaEngineMode,
        activeRouter: KapamilyaUnicodeRouterMode,
        activeDialect: KapamilyaDialectMode,
        rate: Float,
        pitch: Float,
        params: Bundle?,
        forcedLocale: Locale?,
        runDirectAudioSync: Boolean
    ) {
        val segLang = seg.langCode
        val isNonJp = !segLang.startsWith("ja")
        val isUnicodeForeign = isNonJp && segLang != "en"

        var segText = applyKapamilyaTaglishPhonetics(seg.text, segLang, activeDialect)
        if (isNonJp) {
            segText = segText.replace("、", ", ").replace("。", ". ").replace("（", "(").replace("）", ")")
        }

        val targetBackend: String = when {
            activeEngine == KapamilyaEngineMode.PIPER_FEMALE -> "piper_female"
            isNonJp && activeRouter == KapamilyaUnicodeRouterMode.PIPER_FEMALE -> "piper_female"
            activeEngine == KapamilyaEngineMode.OPEN_JTALK && !isNonJp -> "open_jtalk"
            isNonJp && activeRouter == KapamilyaUnicodeRouterMode.AUTO_LOCALE -> "google_or_piper_female"
            isUnicodeForeign && activeRouter != KapamilyaUnicodeRouterMode.OFF -> activeRouter.id
            activeEngine in listOf(
                KapamilyaEngineMode.GOOGLE,
                KapamilyaEngineMode.ESPEAK,
                KapamilyaEngineMode.SAMSUNG
            ) -> activeEngine.id
            activeEngine == KapamilyaEngineMode.ESPEAK_EN && isNonJp -> "espeak"
            else -> "system"
        }

        val segLocale = forcedLocale ?: resolveLocaleForSegment(segLang)

        if (targetBackend == "open_jtalk") {
            val model = KapamilyaOpenJTalkSynthesizer.getSelectedVoiceModel(context)
            val action = Runnable {
                KapamilyaOpenJTalkSynthesizer.speakDirectAudioTrack(
                    context = context,
                    text = segText,
                    voiceModel = model,
                    speedMultiplier = rate,
                    pitchMultiplier = pitch,
                    flush = (segQueueMode == TextToSpeech.QUEUE_FLUSH)
                )
            }
            if (runDirectAudioSync) action.run() else bgExecutor.execute(action)
        } else if (targetBackend == "piper_female") {
            val piperModel = PiperFemaleVoiceModel.forLanguageOrVoiceId(segLang, activeDialect)
            val action = Runnable {
                KapamilyaPiperSynthesizer.speakDirectFemaleAudioTrack(
                    context = context,
                    text = segText,
                    model = piperModel,
                    speedMultiplier = rate,
                    pitchMultiplier = pitch,
                    flush = (segQueueMode == TextToSpeech.QUEUE_FLUSH)
                )
            }
            if (runDirectAudioSync) action.run() else bgExecutor.execute(action)
        } else if (targetBackend == "google_or_piper_female") {
            val googleTts = getOrInitSecondaryTts("com.google.android.tts")
            val chosenTts = googleTts ?: primaryTts
            val avail = try { chosenTts.isLanguageAvailable(segLocale) } catch (_: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
            if (avail >= TextToSpeech.LANG_AVAILABLE) {
                speakWithAndroidTts(chosenTts, segText, segLocale, segQueueMode, rate, pitch, params)
            } else {
                val piperModel = PiperFemaleVoiceModel.forLanguageOrVoiceId(segLang, activeDialect)
                val action = Runnable {
                    KapamilyaPiperSynthesizer.speakDirectFemaleAudioTrack(
                        context = context,
                        text = segText,
                        model = piperModel,
                        speedMultiplier = rate,
                        pitchMultiplier = pitch,
                        flush = (segQueueMode == TextToSpeech.QUEUE_FLUSH)
                    )
                }
                if (runDirectAudioSync) action.run() else bgExecutor.execute(action)
            }
        } else if (targetBackend in listOf("espeak", "google", "samsung")) {
            val pkg = when (targetBackend) {
                "espeak" -> "com.reecedunn.espeak"
                "google" -> "com.google.android.tts"
                "samsung" -> "com.samsung.SMT"
                else -> null
            }
            val secTts = if (pkg != null) getOrInitSecondaryTts(pkg) else null
            val chosenTts = secTts ?: primaryTts
            speakWithAndroidTts(chosenTts, segText, segLocale, segQueueMode, rate, pitch, params)
        } else {
            speakWithAndroidTts(primaryTts, segText, segLocale, segQueueMode, rate, pitch, params)
        }
    }

    fun speakMultilingual(
        rawText: String,
        primaryTts: TextToSpeech?,
        queueMode: Int,
        rate: Float,
        pitch: Float,
        params: Bundle?,
        forcedLocale: Locale? = null
    ): Boolean {
        if (rawText.isBlank() || primaryTts == null) return false

        val activeEngine = engineMode
        val activeRouter = unicodeRouterMode
        val activeDialect = englishDialectMode

        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            stop()
            try {
                primaryTts.stop()
            } catch (_: Exception) {}
        }

        val myGen = speechGeneration.incrementAndGet()
        val normalizedText = splitCamelCaseIdentifiers(rawText)
        val segments = if (forcedLocale != null) {
            listOf(MultilingualSegment(forcedLocale.toLanguageTag().lowercase(Locale.ROOT), normalizedText))
        } else {
            splitMultilingualSegments(normalizedText, hintLang = "ja").ifEmpty {
                listOf(MultilingualSegment("ja", normalizedText))
            }
        }

        dispatchSingleSegment(
            seg = segments[0],
            segQueueMode = queueMode,
            primaryTts = primaryTts,
            activeEngine = activeEngine,
            activeRouter = activeRouter,
            activeDialect = activeDialect,
            rate = rate,
            pitch = pitch,
            params = params,
            forcedLocale = forcedLocale,
            runDirectAudioSync = false
        )

        if (segments.size > 1) {
            bgExecutor.execute {
                for (i in 1 until segments.size) {
                    if (speechGeneration.get() != myGen) return@execute
                    try { Thread.sleep(85) } catch (_: Exception) {}
                    var waitedMs = 0
                    while (waitedMs < 15000 && speechGeneration.get() == myGen && isAnyTtsSpeaking(primaryTts)) {
                        try { Thread.sleep(45) } catch (_: Exception) {}
                        waitedMs += 45
                    }
                    if (speechGeneration.get() != myGen) return@execute
                    dispatchSingleSegment(
                        seg = segments[i],
                        segQueueMode = TextToSpeech.QUEUE_FLUSH,
                        primaryTts = primaryTts,
                        activeEngine = activeEngine,
                        activeRouter = activeRouter,
                        activeDialect = activeDialect,
                        rate = rate,
                        pitch = pitch,
                        params = params,
                        forcedLocale = forcedLocale,
                        runDirectAudioSync = true
                    )
                }
            }
        }
        return true
    }

    private fun selectFemaleVoiceForLocale(tts: TextToSpeech, targetLocale: Locale) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            val voices = tts.voices ?: return
            val lang = targetLocale.language.lowercase(Locale.ROOT)
            val country = targetLocale.country.lowercase(Locale.ROOT)
            val matching = voices.filter { v ->
                val vLang = v.locale.language.lowercase(Locale.ROOT)
                (vLang == lang || (lang in listOf("fil", "tl") && vLang in listOf("fil", "tl", "tgl")))
            }
            if (matching.isEmpty()) return
            // Prefer highest-quality Natural / Neural female voice for every language (Tagalog, Japanese, English, Spanish, etc.)
            val bestFemale = matching.sortedByDescending { v ->
                val n = v.name.lowercase(Locale.ROOT)
                val f = v.features?.joinToString(",")?.lowercase(Locale.ROOT) ?: ""
                var score = 0
                // 1. Acoustic / Natural Voice Quality (QUALITY_VERY_HIGH = 500, QUALITY_HIGH = 400, QUALITY_NORMAL = 300)
                score += (v.quality / 10)
                if ("natural" in n || "neural" in n || "wavenet" in n || "studio" in n || "journey" in n) score += 45
                if ("blessica" in n || "nanami" in n || "kyoko" in n || "aria" in n || "jenny" in n || "natasha" in n) score += 40
                // 2. Country / Dialect match
                if (v.locale.country.lowercase(Locale.ROOT) == country) score += 15
                // 3. Female voice preference
                if ("female" in n || "female" in f || "-f-" in n || "sfg" in n || "tpf" in n || "iol" in n || "sfs" in n || "htm" in n) score += 25
                if ("male" in n && "female" !in n) score -= 35
                // 4. Low latency preference when quality is equal
                if (!v.isNetworkConnectionRequired) score += 8
                score
            }.firstOrNull()
            if (bestFemale != null) {
                tts.voice = bestFemale
            }
        } catch (_: Exception) {}
    }

    private fun speakWithAndroidTts(
        tts: TextToSpeech,
        text: String,
        targetLocale: Locale,
        queueMode: Int,
        rate: Float,
        pitch: Float,
        params: Bundle?
    ) {
        try {
            tts.setSpeechRate(rate)
            tts.setPitch(pitch)
            val avail = tts.isLanguageAvailable(targetLocale)
            if (avail >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = targetLocale
                selectFemaleVoiceForLocale(tts, targetLocale)
            } else if (targetLocale.language == "fil" || targetLocale.language == "tl") {
                val tlLocale = Locale.forLanguageTag("tl-PH")
                if (tts.isLanguageAvailable(tlLocale) >= TextToSpeech.LANG_AVAILABLE) {
                    tts.language = tlLocale
                    selectFemaleVoiceForLocale(tts, tlLocale)
                } else {
                    tts.language = Locale.US
                    selectFemaleVoiceForLocale(tts, Locale.US)
                }
            } else if (targetLocale.language == "en") {
                tts.language = Locale.US
                selectFemaleVoiceForLocale(tts, Locale.US)
            }
        } catch (_: Exception) {}

        val utteranceId = "kapamilyaSeg_${System.nanoTime()}"
        val res = tts.speak(text, queueMode, params, utteranceId)
        if (res == TextToSpeech.ERROR) {
            tts.speak(text, queueMode, null, utteranceId)
        }
    }
}
