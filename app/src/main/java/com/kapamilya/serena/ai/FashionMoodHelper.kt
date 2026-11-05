package com.kapamilya.serena.ai

import android.graphics.Color
import com.kapamilya.serena.translation.SerenaLocalizationBridge
import kotlin.math.max
import kotlin.math.min

/**
 * FashionMoodHelper
 *
 * 生まれつき視覚を持たない全盲ユーザーのために、
 * 単なる「赤」「青」といった記号的な色名ではなく、
 * 「温度感（あたたかさ・涼しさ）」「光のトーン」「肌ざわりの質感」「雰囲気・印象」
 * に翻訳して心に情景を届ける、Kapamilya Serena 独自のマルチリンガル（日・英・タガログ・スペイン・オランダ語）
 * ファッション＆色彩情景エンジン（EmbeddingGemma 2 視覚特徴量対応）。
 */
object FashionMoodHelper {

    data class FashionMoodDescription(
        val simpleColorName: String,
        val sensoryTone: String,
        val emotionalVibe: String,
        val fullSpokenDescription: String
    )

    private fun buildMood(
        nameJa: String, nameEn: String, nameTl: String, nameEs: String, nameNl: String,
        toneJa: String, toneEn: String,
        vibeJa: String, vibeEn: String,
        fullJa: String, fullEn: String, fullTl: String, fullEs: String, fullNl: String
    ): FashionMoodDescription {
        return FashionMoodDescription(
            simpleColorName = SerenaLocalizationBridge.select(nameJa, nameEn, nameTl, nameEs, nameNl),
            sensoryTone = SerenaLocalizationBridge.select(toneJa, toneEn, toneEn, toneEn, toneEn),
            emotionalVibe = SerenaLocalizationBridge.select(vibeJa, vibeEn, vibeEn, vibeEn, vibeEn),
            fullSpokenDescription = SerenaLocalizationBridge.select(fullJa, fullEn, fullTl, fullEs, fullNl)
        )
    }

    /**
     * RGB値から、全盲の感覚に直感的に届く多言語の色彩ムード表現を生成
     */
    fun describeColorFromRgb(r: Int, g: Int, b: Int): FashionMoodDescription {
        val brightness = (r * 299 + g * 587 + b * 114) / 1000
        val maxVal = max(r, max(g, b))
        val minVal = min(r, min(g, b))
        val delta = maxVal - minVal
        val saturation = if (maxVal == 0) 0f else delta.toFloat() / maxVal

        // 1. 白系（クリアホワイト、クリーム）
        if (brightness >= 210) {
            return if (saturation < 0.12f) {
                buildMood(
                    "クリアホワイト", "Clear White", "Maaliwalas na Puti", "Blanco Puro", "Helder Wit",
                    "曇りのないパリッとした清潔感のあるトーン", "Crisp, clean bright tone",
                    "清潔感にあふれた明るく清らかな印象", "Fresh, bright, and pure impression",
                    "パリッと清潔感のあるクリアホワイト。明るく清らかな印象です。",
                    "Crisp, clean clear white. Gives a bright and fresh impression.",
                    "Maaliwalas at malinis na puti. Maliwanag at preskong tingnan.",
                    "Blanco puro y nítido. Transmite una impresión fresca y luminosa.",
                    "Fris en helder wit. Geeft een lichte en verzorgde indruk."
                )
            } else {
                buildMood(
                    "クリームホワイト", "Cream White", "Kremang Puti", "Blanco Crema", "Crèmewit",
                    "ミルクのようにほんのり温かみを感じる柔らかなトーン", "Soft, warm milk-like tone",
                    "優しく穏やかで親しみやすい雰囲気", "Gentle, calm, and welcoming vibe",
                    "ミルクのようにほんのり温かみのあるクリームホワイト。優しく穏やかな雰囲気です。",
                    "Soft, warm cream white. Creates a gentle and welcoming atmosphere.",
                    "Malambot at mainit na kremang puti. Maamo at payapang tingnan.",
                    "Blanco crema suave y cálido. Crea un ambiente acogedor y sereno.",
                    "Zacht, warm crèmewit. Geeft een vriendelijke en rustige uitstraling."
                )
            }
        }

        // 2. 黒系（漆黒、チャコール）
        if (brightness <= 40) {
            return buildMood(
                "漆黒ブラック", "Jet Black", "anghang Itim", "Negro Azabache", "Diepzwart",
                "キリッと全体が美しく引き締まる深みのあるトーン", "Deep, sharp, and sleek tone",
                "上品でフォーマルなかっこいい印象", "Elegant, formal, and stylish impression",
                "キリッと全体が引き締まる漆黒ブラック。上品でシックなかっこいい印象です。",
                "Sharp, sleek jet black. Gives an elegant, chic, and confident impression.",
                "Matapang at elegante na itim. Pormal at makisig ang dating.",
                "Negro azabache elegante y definido. Transmite estilo y sofisticación.",
                "Strak diepzwart. Geeft een elegante, chique en stijlvolle indruk."
            )
        }

        // 3. 低彩度（グレー、チャコール）
        if (saturation < 0.18f) {
            return if (brightness > 130) {
                buildMood(
                    "ライトグレー", "Light Gray", "Mapusyaw na Abo", "Gris Claro", "Lichtgrijs",
                    "ふんわりと柔らかく風通しのよい軽快なトーン", "Soft, airy, and light tone",
                    "洗練されていて肩の力が抜けた自然体の印象", "Refined, relaxed, and natural look",
                    "柔らかく軽快なライトグレー。洗練された自然体の印象です。",
                    "Soft, airy light gray. A refined and naturally relaxed look.",
                    "Maaliwalas na light gray. Simple at elegante.",
                    "Gris claro suave y ligero. Un estilo refinado y natural.",
                    "Zacht en luchtig lichtgrijs. Een verfijnde en ontspannen uitstraling."
                )
            } else {
                buildMood(
                    "チャコールグレー", "Charcoal Gray", "Madilim na Abo", "Gris Carbón", "Antracietgrijs",
                    "落ち着きのあるシックで上品な深みトーン", "Calm, chic, and deep tone",
                    "大人っぽく落ち着いた知的な雰囲気", "Mature, composed, and intelligent vibe",
                    "落ち着きのあるチャコールグレー。大人っぽく知的な雰囲気です。",
                    "Composed charcoal gray. Gives a mature and intelligent vibe.",
                    "Mahinahon na charcoal gray. Kagalang-galang at matalinong dating.",
                    "Gris carbón sereno. Transmite madurez y elegancia intelectual.",
                    "Rustig antracietgrijs. Geeft een volwassen en intelligente indruk."
                )
            }
        }

        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]

        return when (hue) {
            in 345f..360f, in 0f..15f -> {
                if (brightness < 100) {
                    buildMood(
                        "ワインレッド", "Wine Red", "Wine Red", "Rojo Vino", "Wijnrood",
                        "暖炉の火のようにじんわりとあたたかい深みトーン", "Rich, warm fireplace-like tone",
                        "落ち着いた大人の温もりと上品な華やかさ", "Warm, refined elegance",
                        "暖炉の火のようにじんわり温かいワインレッド。落ち着いた大人の上品な華やかさです。",
                        "Warm, rich wine red like a cozy fireplace. Refined and graceful elegance.",
                        "Mainit at malalim na wine red. Elegante at kaaya-ayang tingnan.",
                        "Rojo vino cálido y profundo. Elegancia madura y acogedora.",
                        "Warm en diep wijnrood. Straalt volwassen warmte en klasse uit."
                    )
                } else {
                    buildMood(
                        "チェリーレッド", "Vibrant Red", "Matingkad na Pula", "Rojo Cereza", "Helderrood",
                        "太陽の光のようにパッと目を引く情熱的なトーン", "Vibrant, energetic sunlit tone",
                        "明るく元気いっぱいでエネルギッシュな印象", "Bright, cheerful, and energetic impression",
                        "パッと目を引く鮮やかなチェリーレッド。明るく元気いっぱいのエネルギッシュな印象です。",
                        "Eye-catching vibrant cherry red. Bright, lively, and full of energy.",
                        "Matingkad at masiglang pula. Puno ng enerhiya at sigla.",
                        "Rojo cereza vibrante. Transmite alegría, fuerza y mucha energía.",
                        "Levendig kersenrood. Een vrolijke en energieke uitstraling."
                    )
                }
            }
            in 16f..45f -> {
                buildMood(
                    "アプリコットオレンジ", "Warm Orange", "Mainit na Kahel", "Naranja Cálido", "Warm Oranje",
                    "太陽や果実のようにあたたかなトーン", "Warm, sunny, and inviting tone",
                    "親しみやすく笑顔がこぼれる明るい雰囲気", "Friendly, cheerful, and approachable vibe",
                    "暖かみあふれるアプリコットオレンジ。親しみやすく明るい雰囲気です。",
                    "Warm apricot orange. Creates a friendly, cheerful, and welcoming vibe.",
                    "Mainit at masayahing kahel. Magiliw at maaliwalas ang dating.",
                    "Naranja albaricoque cálido. Ambiente cercano, alegre y acogedor.",
                    "Warm abrikoosoranje. Een vriendelijke en zonnige uitstraling."
                )
            }
            in 46f..70f -> {
                buildMood(
                    "陽だまりイエロー", "Sunny Yellow", "Maaliwalas na Dilaw", "Amarillo Soleado", "Zonnig Geel",
                    "春のぽかぽかした陽射しを浴びているようなトーン", "Warm spring sunshine tone",
                    "見ているだけで楽しくなる元気でフレンドリーな印象", "Joyful, bright, and friendly impression",
                    "ぽかぽか陽だまりのようなイエロー。見ているだけで楽しくなるフレンドリーな印象です。",
                    "Sunny, warm yellow like spring sunshine. Cheerful and friendly impression.",
                    "Parang sikat ng araw na dilaw. Masaya at magiliw tingnan.",
                    "Amarillo soleado como un día de primavera. Alegre y muy amigable.",
                    "Zonnig geel als een lentedag. Vrolijk en toegankelijk."
                )
            }
            in 71f..165f -> {
                buildMood(
                    "ナチュラルグリーン", "Natural Green", "Sariwang Berde", "Verde Natural", "Natuurlijk Groen",
                    "草原や森を吹き抜ける風のようにみずみずしく爽快なトーン", "Fresh, breezy forest and meadow tone",
                    "リフレッシュ感のある優しく穏やかな印象", "Refreshing, calm, and trustworthy impression",
                    "草原の風のようにみずみずしいグリーン。爽やかで心安らぐ印象です。",
                    "Fresh natural green like a gentle breeze through trees. Calm and refreshing.",
                    "Sariwang berde na parang hangin sa kalikasan. Payapa at presko.",
                    "Verde natural y fresco como la brisa del bosque. Sereno y relajante.",
                    "Fris natuurlijk groen als een briesje in het bos. Rustgevend en verfrissend."
                )
            }
            in 166f..260f -> {
                buildMood(
                    "クリアスカイブルー", "Sky / Royal Blue", "Maaliwalas na Asul", "Azul Cielo", "Helderblauw",
                    "澄み切った青空や海のように涼やかで透明感のあるトーン", "Cool, clear sky and ocean tone",
                    "爽快で涼しげな、清潔感と知性あふれる印象", "Cool, clean, smart, and refreshing impression",
                    "澄んだ青空のように涼やかなブルー。清潔感と知性あふれる爽快な印象です。",
                    "Cool, clear blue like a bright sky. Clean, smart, and refreshing impression.",
                    "Maaliwalas na asul gaya ng langit. Malinis, matalino, at presko.",
                    "Azul claro y fresco como el cielo despejado. Limpio, elegante y refrescante.",
                    "Helder en fris blauw als een strakke hemel. Verzorgd en slim."
                )
            }
            in 261f..315f -> {
                buildMood(
                    "ラベンダーパープル", "Lavender Purple", "Lila / Lavender", "Púrpura Lavanda", "Lavendelpaars",
                    "そよ風に揺れる花のように優美なトーン", "Graceful, floral, and noble tone",
                    "気品があり、とても優雅で穏やかな雰囲気", "Graceful, dignified, and serene vibe",
                    "そよ風に揺れる花のようなラベンダーパープル。優雅で心安らぐ雰囲気です。",
                    "Graceful lavender purple. Radiates elegance, dignity, and calm.",
                    "Mahinhin at eleganteng lila. Payapa at marangal ang dating.",
                    "Púrpura lavanda elegante. Transmite distinción, calma y armonía.",
                    "Sierlijk lavendelpaars. Straalt rust en verfijnde klasse uit."
                )
            }
            else -> {
                buildMood(
                    "華やかローズピンク", "Blossom Pink", "Masiglang Rosas", "Rosa Cálido", "Bloesemroze",
                    "春の陽気のように暖かく華やぐトーン", "Warm, blooming spring blossom tone",
                    "周りをパッと明るくする愛らしく魅力的な印象", "Warm, charming, and uplifting impression",
                    "パッと華やぐ温かいピンク。周りを明るくする愛らしく魅力的な印象です。",
                    "Warm blossom pink. Brightens the surroundings with a charming and lovely vibe.",
                    "Masigla at mainit na rosas. Nakakapagpasaya at kaakit-akit.",
                    "Rosa cálido y radiante. Ilumina el entorno con encanto y ternura.",
                    "Warm bloesemroze. Vrolijkt de omgeving op met een charmante uitstraling."
                )
            }
        }
    }
}
