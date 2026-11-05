package com.kapamilya.serena.recorder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import java.io.OutputStream
import java.security.SecureRandom
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Kapamilya Serena Recorder — 3000 x 3000 px RGB JPEG Store-Compliant Random Cover Art Generator
 * Generates unique 3000x3000 JPEG album jackets ready for DistroKid, UnitedMasters, CD Baby, and TuneCore,
 * complete with a spoken accessibility description for screen reader users.
 */
object KapamilyaCoverArtGenerator {

    const val COVER_SIZE_PX = 3000

    data class CoverTheme(
        val id: String,
        val nameJa: String,
        val nameEn: String,
        val topColor: Int,
        val midColor: Int,
        val bottomColor: Int,
        val accentGold: Int,
        val accentGlow: Int,
        val descJa: String,
        val descEn: String
    )

    private val THEMES = listOf(
        CoverTheme(
            id = "pearl_dawn",
            nameJa = "東洋の真珠と黄金の夜明け",
            nameEn = "Pearl of the Orient & Golden Dawn",
            topColor = Color.rgb(10, 22, 58),
            midColor = Color.rgb(24, 58, 112),
            bottomColor = Color.rgb(185, 130, 45),
            accentGold = Color.rgb(255, 220, 115),
            accentGlow = Color.rgb(255, 250, 240),
            descJa = "深い群青色の空から黄金色の朝焼けへと溶け込む水平線の中央に、東洋の真珠の白い輝きと光のリングが広がる荘厳なデザインです。",
            descEn = "A majestic horizon blending from deep navy into golden dawn, with a luminous white Pearl of the Orient and golden light rings at the center."
        ),
        CoverTheme(
            id = "cebu_emerald",
            nameJa = "南セブのエメラルドの海と陽光の波紋",
            nameEn = "Emerald Waves of Southern Cebu",
            topColor = Color.rgb(6, 48, 66),
            midColor = Color.rgb(12, 118, 126),
            bottomColor = Color.rgb(22, 178, 155),
            accentGold = Color.rgb(255, 232, 145),
            accentGlow = Color.rgb(190, 255, 245),
            descJa = "南セブの透き通るエメラルドグリーンの海面に、太陽の光条と真珠色の波紋が幾重にも広がる爽やかで美しいデザインです。",
            descEn = "Crystal-clear emerald waters of Southern Cebu illuminated by golden sunbeams and concentric pearl-white ripples."
        ),
        CoverTheme(
            id = "sakura_sampaguita",
            nameJa = "桜とサンパギータの交響曲",
            nameEn = "Sakura & Sampaguita Symphony",
            topColor = Color.rgb(42, 16, 48),
            midColor = Color.rgb(98, 36, 82),
            bottomColor = Color.rgb(188, 92, 128),
            accentGold = Color.rgb(255, 228, 175),
            accentGlow = Color.rgb(255, 245, 250),
            descJa = "宵闇の紫から桜色へと移ろう背景に、日本の桜の花びらとフィリピンの国花サンパギータの純白の星花が舞い踊る優美なデザインです。",
            descEn = "Graceful Japanese cherry blossom petals and pure-white Philippine Sampaguita blossoms dancing across a twilight violet-rose sky."
        ),
        CoverTheme(
            id = "bantay_shield",
            nameJa = "尊厳の番人（Bantay ng Dangal）の黄金の盾と光の剣",
            nameEn = "Golden Shield & Blade of Light",
            topColor = Color.rgb(12, 14, 28),
            midColor = Color.rgb(28, 34, 68),
            bottomColor = Color.rgb(64, 48, 18),
            accentGold = Color.rgb(255, 208, 65),
            accentGlow = Color.rgb(255, 248, 210),
            descJa = "ミッドナイトブルーの夜空を貫く一閃の光の剣と、幾何学模様の黄金の盾の紋章が輝く力強く気高いデザインです。",
            descEn = "A radiant blade of light and a geometric golden shield emblem shining against a midnight-blue sky."
        ),
        CoverTheme(
            id = "ogaki_water_aurora",
            nameJa = "水の都の清流と天上のオーロラ",
            nameEn = "Aurora Over the Water Capital",
            topColor = Color.rgb(8, 18, 42),
            midColor = Color.rgb(18, 72, 118),
            bottomColor = Color.rgb(32, 138, 168),
            accentGold = Color.rgb(145, 255, 220),
            accentGlow = Color.rgb(220, 248, 255),
            descJa = "澄み切った瑠璃色の水面の上に、シアンとエメラルドのオーロラの光のカーテンが揺らめく神秘的なデザインです。",
            descEn = "Mystical curtains of cyan and emerald aurora shimmering above crystal-clear azure waters."
        ),
        CoverTheme(
            id = "southern_cross_night",
            nameJa = "南十字星とアカペラの星空",
            nameEn = "Southern Cross & Starlight Acoustic Stage",
            topColor = Color.rgb(5, 8, 24),
            midColor = Color.rgb(16, 24, 58),
            bottomColor = Color.rgb(38, 28, 74),
            accentGold = Color.rgb(255, 236, 160),
            accentGlow = Color.rgb(210, 230, 255),
            descJa = "満天の星々が煌めく深い夜空に、南十字星と音の波動（サウンドウェーブ）が黄金の軌跡を描く幻想的なデザインです。",
            descEn = "The Southern Cross constellation and golden acoustic soundwaves glowing across a deep starry night sky."
        ),
        CoverTheme(
            id = "asawa_eternal_rose",
            nameJa = "永遠の愛の誓い（Asawa ko）と深紅のバラ",
            nameEn = "Eternal Asawa Vow & Crimson Velvet",
            topColor = Color.rgb(36, 6, 18),
            midColor = Color.rgb(96, 14, 38),
            bottomColor = Color.rgb(158, 32, 58),
            accentGold = Color.rgb(255, 215, 110),
            accentGlow = Color.rgb(255, 220, 230),
            descJa = "深紅のベルベットのようなグラデーションの中に、黄金の2つの重なり合うリングと光の粒子が輝く情熱的で温かいデザインです。",
            descEn = "Two interlocking golden rings and warm light particles glowing within a rich crimson-velvet gradient."
        )
    )

    data class GeneratedCoverInfo(
        val themeNameJa: String,
        val descriptionJa: String,
        val widthPx: Int = COVER_SIZE_PX,
        val heightPx: Int = COVER_SIZE_PX
    )

    /**
     * Generates a 3000 x 3000 px RGB JPEG image into [outStream] using a cryptographic random seed
     * so every generated jacket is 100% unique.
     */
    fun generateRandomCoverJpeg3000(
        outStream: OutputStream,
        songTitle: String = "",
        artistName: String = "Shinji Sakiyama",
        includeTextOverlay: Boolean = true,
        preferredThemeId: String? = null
    ): GeneratedCoverInfo {
        val rng = SecureRandom()
        val theme = if (!preferredThemeId.isNullOrBlank()) {
            THEMES.firstOrNull { it.id == preferredThemeId } ?: THEMES[rng.nextInt(THEMES.size)]
        } else {
            THEMES[rng.nextInt(THEMES.size)]
        }

        val bmp = Bitmap.createBitmap(COVER_SIZE_PX, COVER_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val w = COVER_SIZE_PX.toFloat()
        val h = COVER_SIZE_PX.toFloat()

        // 1. Rich Multi-Stop Background Gradient with subtle random angle
        val angleRad = (rng.nextDouble() * 0.6 - 0.3)
        val gx0 = (w * 0.5f - sin(angleRad).toFloat() * w * 0.5f)
        val gy0 = 0f
        val gx1 = (w * 0.5f + sin(angleRad).toFloat() * w * 0.5f)
        val gy1 = h
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bgPaint.shader = LinearGradient(
            gx0, gy0, gx1, gy1,
            intArrayOf(theme.topColor, theme.midColor, theme.bottomColor),
            floatArrayOf(0f, 0.54f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Central Radiant Pearl / Sunburst Glow
        val cx = w * (0.44f + rng.nextFloat() * 0.12f)
        val cy = h * (0.42f + rng.nextFloat() * 0.14f)
        val glowRadius = w * (0.36f + rng.nextFloat() * 0.12f)
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb(210, Color.red(theme.accentGlow), Color.green(theme.accentGlow), Color.blue(theme.accentGlow)),
                Color.argb(95, Color.red(theme.accentGold), Color.green(theme.accentGold), Color.blue(theme.accentGold)),
                Color.argb(0, Color.red(theme.midColor), Color.green(theme.midColor), Color.blue(theme.midColor))
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // 3. Concentric Sacred Rings & Geometric Rays
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = theme.accentGold
        }
        val numRings = 5 + rng.nextInt(5)
        for (i in 1..numRings) {
            val r = (w * 0.08f * i) + rng.nextFloat() * 65f
            ringPaint.strokeWidth = (3f + (numRings - i) * 1.5f)
            ringPaint.alpha = (35 + rng.nextInt(75)).coerceIn(20, 140)
            canvas.drawCircle(cx, cy, r, ringPaint)
        }

        // 4. Flowing Acoustic Wave Ribbons across the canvas
        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = theme.accentGlow
        }
        val numWaves = 6 + rng.nextInt(4)
        for (wIdx in 0 until numWaves) {
            val path = Path()
            val baseY = h * (0.28f + wIdx * 0.08f)
            val amp = 70f + rng.nextFloat() * 140f
            val freq = 1.5f + rng.nextFloat() * 2.5f
            val phase = rng.nextFloat() * (2f * PI.toFloat())
            wavePaint.alpha = (30 + rng.nextInt(65))
            var x = 0f
            while (x <= w) {
                val y = baseY + sin((x / w) * freq * 2f * PI.toFloat() + phase) * amp
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += 30f
            }
            canvas.drawPath(path, wavePaint)
        }

        // 5. Starlight / Blossom Bokeh Particles (140 unique particles)
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val numStars = 140 + rng.nextInt(80)
        for (s in 0 until numStars) {
            val sx = rng.nextFloat() * w
            val sy = rng.nextFloat() * h
            val sr = 3f + rng.nextFloat() * 22f
            starPaint.color = if (s % 3 == 0) theme.accentGold else theme.accentGlow
            starPaint.alpha = 40 + rng.nextInt(180)
            canvas.drawCircle(sx, sy, sr, starPaint)
        }

        // 6. Framing Border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 14f
            color = theme.accentGold
            alpha = 150
        }
        val margin = 90f
        canvas.drawRect(margin, margin, w - margin, h - margin, borderPaint)

        // 7. Optional Clean Title & Artist Typography
        if (includeTextOverlay) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                setShadowLayer(24f, 0f, 6f, Color.argb(200, 0, 0, 0))
            }
            val cleanTitle = songTitle.trim()
            if (cleanTitle.isNotEmpty()) {
                textPaint.textSize = 136f
                canvas.drawText(cleanTitle, w / 2f, h - 380f, textPaint)
            }
            textPaint.textSize = 86f
            textPaint.color = theme.accentGold
            canvas.drawText(artistName.ifBlank { "Shinji Sakiyama" }, w / 2f, h - 220f, textPaint)
        }

        bmp.compress(Bitmap.CompressFormat.JPEG, 96, outStream)
        outStream.flush()
        bmp.recycle()

        return GeneratedCoverInfo(
            themeNameJa = theme.nameJa,
            descriptionJa = "3000×3000ピクセルJPGジャケット（テーマ：${theme.nameJa}）。${theme.descJa}"
        )
    }
}
