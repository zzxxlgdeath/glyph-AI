package com.example.glyph

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders 25x25 LED frames (int[625], brightness 0..255) for the Nothing Phone (3) Glyph Matrix.
 * Supports full Cyrillic (А-Я, Ё, Й), Latin (A-Z with NFD diacritic stripping), Digits (0-9),
 * and extended symbols, plus procedural animations for IDLE, LISTENING, THINKING, and ERROR states.
 */
object PixelMatrixRenderer {
    const val MATRIX_SIZE = 25
    const val TOTAL_PIXELS = MATRIX_SIZE * MATRIX_SIZE

    private val COMBINING_MARKS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

    // 5x7 pixel font map (each string has 7 rows of 5 characters: '#' = lit, '.' = off)
    private val FONT_5X7: Map<Char, List<String>> = mapOf(
        // Digits
        '0' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to listOf(".###.", "#...#", "....#", "..##.", ".#...", "#....", "#####"),
        '3' to listOf("#####", "....#", "...#.", "..##.", "....#", "#...#", ".###."),
        '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to listOf(".###.", "#....", "####.", "#...#", "#...#", "#...#", ".###."),
        '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),

        // Latin
        'A' to listOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'B' to listOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to listOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'D' to listOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
        'E' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to listOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###."),
        'H' to listOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to listOf(".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."),
        'J' to listOf("..###", "...#.", "...#.", "...#.", "#..#.", "#..#.", ".##.."),
        'K' to listOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to listOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to listOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'N' to listOf("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"),
        'O' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to listOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to listOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to listOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to listOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'W' to listOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"),
        'X' to listOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to listOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        'Z' to listOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),

        // Cyrillic
        'А' to listOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'Б' to listOf("#####", "#....", "#....", "####.", "#...#", "#...#", "####."),
        'В' to listOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'Г' to listOf("#####", "#....", "#....", "#....", "#....", "#....", "#...."),
        'Д' to listOf("..##.", ".#..#", ".#..#", ".#..#", ".#..#", "#####", "#...#"),
        'Е' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'Ё' to listOf(".#.#.", "#####", "#....", "####.", "#....", "#....", "#####"),
        'Ж' to listOf("#.#.#", "#.#.#", ".###.", "..#..", ".###.", "#.#.#", "#.#.#"),
        'З' to listOf(".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."),
        'И' to listOf("#...#", "#..##", "#.#.#", "#.#.#", "##..#", "#...#", "#...#"),
        'Й' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", "#...#"),
        'К' to listOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'Л' to listOf("..###", ".#..#", ".#..#", ".#..#", ".#..#", ".#..#", "#...#"),
        'М' to listOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'Н' to listOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'О' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'П' to listOf("#####", "#...#", "#...#", "#...#", "#...#", "#...#", "#...#"),
        'Р' to listOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'С' to listOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'Т' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'У' to listOf("#...#", "#...#", "#...#", ".####", "....#", "#...#", ".###."),
        'Ф' to listOf(".###.", "#.#.#", "#.#.#", "#.#.#", ".###.", "..#..", "..#.."),
        'Х' to listOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Ц' to listOf("#..#.", "#..#.", "#..#.", "#..#.", "#..#.", "#####", "....#"),
        'Ч' to listOf("#...#", "#...#", "#...#", ".####", "....#", "....#", "....#"),
        'Ш' to listOf("#.#.#", "#.#.#", "#.#.#", "#.#.#", "#.#.#", "#.#.#", "#####"),
        'Щ' to listOf("#.#.#", "#.#.#", "#.#.#", "#.#.#", "#.#.#", "#####", "....#"),
        'Ъ' to listOf("##...", ".#...", ".#...", ".###.", ".#..#", ".#..#", ".###."),
        'Ы' to listOf("#...#", "#...#", "#...#", "###.#", "#.#.#", "#.#.#", "###.#"),
        'Ь' to listOf("#....", "#....", "#....", "####.", "#...#", "#...#", "####."),
        'Э' to listOf(".###.", "#...#", "....#", "..###", "....#", "#...#", ".###."),
        'Ю' to listOf("#.##.", "#.#.#", "#.#.#", "###.#", "#.#.#", "#.#.#", "#.##."),
        'Я' to listOf(".####", "#...#", "#...#", ".####", "..#.#", ".#..#", "#...#"),

        // Punctuation & symbols
        ' ' to listOf("...", "...", "...", "...", "...", "...", "..."),
        '.' to listOf("...", "...", "...", "...", "...", ".#.", ".#."),
        ',' to listOf("...", "...", "...", "...", "...", ".#.", "#.."),
        '\'' to listOf(".#.", ".#.", "...", "...", "...", "...", "..."),
        ':' to listOf("...", ".#.", "...", "...", "...", ".#.", "..."),
        ';' to listOf("...", ".#.", "...", "...", "...", ".#.", "#.."),
        '!' to listOf(".#.", ".#.", ".#.", ".#.", ".#.", "...", ".#."),
        '?' to listOf(".###.", "#...#", "....#", "..##.", "..#..", ".....", "..#.."),
        '-' to listOf(".....", ".....", ".....", ".###.", ".....", ".....", "....."),
        '_' to listOf(".....", ".....", ".....", ".....", ".....", ".....", "#####"),
        '+' to listOf(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
        '=' to listOf(".....", ".....", "#####", ".....", "#####", ".....", "....."),
        '/' to listOf("....#", "...#.", "...#.", "..#..", ".#...", ".#...", "#...."),
        '%' to listOf("##..#", "##.#.", "...#.", "..#..", ".#...", ".#.##", "#..##"),
        '$' to listOf("..#..", ".####", "#.#..", ".###.", "..#.#", "####.", "..#.."),
        '€' to listOf(".###.", "#....", "####.", "#....", "####.", "#....", ".###."),
        '&' to listOf(".##..", "#..#.", ".##..", ".###.", "#.#.#", "#..#.", ".##.#"),
        '(' to listOf("..#.", ".#..", "#...", "#...", "#...", ".#..", "..#."),
        ')' to listOf(".#..", "..#.", "...#", "...#", "...#", "..#.", ".#.."),
        '°' to listOf(".##.", "#..#", "#..#", ".##.", "....", "....", "...."),
        '×' to listOf(".....", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "....."),
        '№' to listOf("#..##", "##.#.", "#.##.", "#..#.", "#..##", ".....", "....."),
        '#' to listOf(".#.#.", "#####", ".#.#.", ".#.#.", "#####", ".#.#.", "....."),
        '@' to listOf(".###.", "#...#", "#.###", "#.#.#", "#.###", "#....", ".###."),
        '>' to listOf("#....", ".#...", "..#..", "...#.", "..#..", ".#...", "#...."),
        '<' to listOf("....#", "...#.", "..#..", ".#...", "..#..", "...#.", "....#"),
        '*' to listOf(".....", "#.#.#", ".###.", "#####", ".###.", "#.#.#", ".....")
    )

    /**
     * Normalizes text for the 5x7 pixel font:
     * - Converts to uppercase
     * - Preserves Russian 'Й' and 'Ё' while stripping Latin diacritics via NFD (e.g. É -> E, Ü -> U)
     * - Normalizes typographic dashes and apostrophes
     */
    fun normalizeForMatrix(text: String): String {
        val raw = text.trim().uppercase()
        if (raw.isEmpty()) return ""

        val protectedCyrillic = raw
            .replace("Й", "\uE001")
            .replace("Ё", "\uE002")
            .replace('—', '-')
            .replace('–', '-')
            .replace('−', '-')
            .replace('’', '\'')
            .replace('‘', '\'')

        val nfd = Normalizer.normalize(protectedCyrillic, Normalizer.Form.NFD)
        val stripped = COMBINING_MARKS_REGEX.replace(nfd, "")

        return stripped
            .replace("\uE001", "Й")
            .replace("\uE002", "Ё")
            .replace('Ł', 'L')
            .replace('Ø', 'O')
            .replace("Æ", "AE")
            .replace("Œ", "OE")
            .replace("ß", "SS")
    }

    /**
     * Checks if a pixel coordinate (x, y) is within the physical Nothing Phone (3) circular LED allocation.
     */
    fun isInsideGlyphCircle(x: Int, y: Int): Boolean {
        val dx = x - 12.0
        val dy = y - 12.0
        return (dx * dx + dy * dy) <= 160.0
    }

    /**
     * Measures the total pixel width of a text string rendered in 5x7 font.
     */
    fun measureTextWidth(text: String): Int {
        val clean = normalizeForMatrix(text)
        if (clean.isEmpty()) return 0
        var width = 0
        clean.forEachIndexed { index, ch ->
            val glyph = FONT_5X7[ch] ?: FONT_5X7['?']!!
            width += glyph[0].length
            if (index < clean.lastIndex) width += 1 // 1px kerning gap
        }
        return width
    }

    /**
     * Determines whether the answer is short enough to be displayed statically on the 25x25 matrix.
     */
    fun shouldDisplayStatically(answer: String): Boolean {
        val clean = normalizeForMatrix(answer)
        if (clean.isEmpty()) return true
        if (clean.length <= 4 && measureTextWidth(clean) <= 23) return true
        return false
    }

    /**
     * Renders short text statically centered on the 25x25 matrix, with subtle decorative top/bottom status bars.
     */
    fun renderStaticAnswer(text: String, brightness: Int = 255): IntArray {
        val frame = IntArray(TOTAL_PIXELS)
        val clean = normalizeForMatrix(text)
        val clampedBrightness = brightness.coerceIn(10, 255)
        val dimBrightness = (clampedBrightness * 0.28f).roundToInt().coerceAtLeast(15)

        for (x in 9..15) {
            setPixel(frame, x, 2, dimBrightness)
            setPixel(frame, x, 22, dimBrightness)
        }
        setPixel(frame, 12, 1, clampedBrightness / 2)
        setPixel(frame, 12, 23, clampedBrightness / 2)

        val totalWidth = measureTextWidth(clean)
        val startX = ((MATRIX_SIZE - totalWidth) / 2).coerceAtLeast(1)
        val startY = (MATRIX_SIZE - 7) / 2 // row 9..15

        drawTextAt(frame, clean, startX, startY, clampedBrightness)
        return frame
    }

    /**
     * Renders a single frame of a scrolling marquee for longer answers (e.g. "RTX 5070").
     */
    fun renderMarqueeAnswer(
        text: String,
        scrollOffsetPx: Int,
        brightness: Int = 255
    ): IntArray {
        val frame = IntArray(TOTAL_PIXELS)
        val clean = normalizeForMatrix(text)
        val clampedBrightness = brightness.coerceIn(10, 255)
        val railBrightness = (clampedBrightness * 0.22f).roundToInt().coerceAtLeast(12)

        for (x in 4..20 step 2) {
            setPixel(frame, x, 5, railBrightness)
            setPixel(frame, x, 19, railBrightness)
        }

        val drawX = MATRIX_SIZE - scrollOffsetPx
        val startY = (MATRIX_SIZE - 7) / 2 // row 9..15
        drawTextAt(frame, clean, drawX, startY, clampedBrightness)
        return frame
    }

    /**
     * Renders the IDLE state: Nothing Oracle geometric eye/diamond gently breathing on the 25x25 grid.
     */
    fun renderIdleGlyph(tick: Int, brightness: Int = 255): IntArray {
        val frame = IntArray(TOTAL_PIXELS)
        val maxB = brightness.coerceIn(20, 255)
        val pulse = (0.55f + 0.45f * sin(tick * 0.18f)).coerceIn(0.2f, 1f)
        val coreB = (maxB * pulse).roundToInt()
        val ringB = (maxB * 0.35f).roundToInt()

        val radius = 8
        for (i in 0..radius) {
            setPixel(frame, 12 + i, 12 - radius + i, ringB)
            setPixel(frame, 12 - i, 12 - radius + i, ringB)
            setPixel(frame, 12 + i, 12 + radius - i, ringB)
            setPixel(frame, 12 - i, 12 + radius - i, ringB)
        }

        setPixel(frame, 12, 12, maxB)
        setPixel(frame, 11, 12, coreB)
        setPixel(frame, 13, 12, coreB)
        setPixel(frame, 12, 11, coreB)
        setPixel(frame, 12, 13, coreB)

        val cornerB = (maxB * 0.5f).roundToInt()
        setPixel(frame, 12, 2, cornerB)
        setPixel(frame, 12, 22, cornerB)
        setPixel(frame, 2, 12, cornerB)
        setPixel(frame, 22, 12, cornerB)

        return frame
    }

    /**
     * Renders the LISTENING state: real-time voice waveform + microphone icon reacting to rmsDb.
     */
    fun renderListeningWave(tick: Int, rmsDb: Float, brightness: Int = 255): IntArray {
        val frame = IntArray(TOTAL_PIXELS)
        val maxB = brightness.coerceIn(30, 255)
        val energy = ((rmsDb + 2f) / 10f).coerceIn(0.18f, 1.0f)

        val barColumns = listOf(3, 6, 9, 12, 15, 18, 21)
        barColumns.forEachIndexed { idx, colX ->
            val phase = tick * 0.45f + idx * 0.9f
            val wave = abs(sin(phase))
            val distanceToCenter = abs(3 - idx)
            val centerBoost = 1.0f - (distanceToCenter * 0.15f)
            val halfHeight = (1 + (8 * energy * wave * centerBoost).roundToInt()).coerceIn(1, 9)

            for (dy in -halfHeight..halfHeight) {
                val y = 12 + dy
                val falloff = 1.0f - (abs(dy).toFloat() / (halfHeight + 2))
                val b = (maxB * falloff).roundToInt().coerceIn(25, maxB)
                setPixel(frame, colX, y, b)
                setPixel(frame, colX + 1, y, (b * 0.8f).roundToInt())
            }
        }

        if (tick % 6 < 4) {
            setPixel(frame, 12, 2, maxB)
            setPixel(frame, 11, 2, maxB / 2)
            setPixel(frame, 13, 2, maxB / 2)
        }
        return frame
    }

    /**
     * Renders the THINKING state: orbiting neural radar while waiting for AI response.
     */
    fun renderThinkingSpinner(tick: Int, brightness: Int = 255): IntArray {
        val frame = IntArray(TOTAL_PIXELS)
        val maxB = brightness.coerceIn(30, 255)

        val numDots = 12
        val radius = 7.0
        for (i in 0 until numDots) {
            val angle = (i * 2.0 * Math.PI / numDots)
            val x = (12 + radius * cos(angle)).roundToInt()
            val y = (12 + radius * sin(angle)).roundToInt()
            val age = (tick - i).mod(numDots)
            val factor = ((numDots - age).toFloat() / numDots)
            val b = (maxB * factor * factor).roundToInt().coerceIn(15, maxB)
            setPixel(frame, x, y, b)
        }

        val innerB = (maxB * (0.4f + 0.6f * abs(sin(tick * 0.3f)))).roundToInt()
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (abs(dx) + abs(dy) <= 1) {
                    setPixel(frame, 12 + dx, 12 + dy, innerB)
                }
            }
        }
        return frame
    }

    /**
     * Renders an ERROR state on the 25x25 matrix ("ERR").
     */
    fun renderErrorFrame(tick: Int, brightness: Int = 255): IntArray {
        val frame = IntArray(TOTAL_PIXELS)
        val maxB = if (tick % 4 < 2) brightness.coerceIn(40, 255) else (brightness * 0.4f).roundToInt()
        drawTextAt(frame, "ERR", 4, 9, maxB)
        return frame
    }

    /**
     * Renders a low-power Always-On Display (AOD) frame for the Nothing Phone (3) Glyph Matrix.
     */
    fun renderAodFrame(lastAnswer: String?, brightness: Int = 90): IntArray {
        if (!lastAnswer.isNullOrBlank() && shouldDisplayStatically(lastAnswer)) {
            return renderStaticAnswer(lastAnswer, brightness.coerceAtMost(90))
        }
        return renderIdleGlyph(0, brightness.coerceAtMost(80))
    }

    private fun drawTextAt(
        frame: IntArray,
        text: String,
        startX: Int,
        startY: Int,
        brightness: Int
    ) {
        var cursorX = startX
        for (ch in text) {
            val pattern = FONT_5X7[ch] ?: FONT_5X7['?']!!
            val charWidth = pattern[0].length
            for (rowIdx in pattern.indices) {
                val row = pattern[rowIdx]
                for (colIdx in 0 until charWidth) {
                    if (row[colIdx] == '#') {
                        setPixel(frame, cursorX + colIdx, startY + rowIdx, brightness)
                    }
                }
            }
            cursorX += charWidth + 1
        }
    }

    private fun setPixel(frame: IntArray, x: Int, y: Int, brightness: Int) {
        if (x in 0 until MATRIX_SIZE && y in 0 until MATRIX_SIZE) {
            val index = y * MATRIX_SIZE + x
            frame[index] = maxOf(frame[index], brightness.coerceIn(0, 255))
        }
    }

    /**
     * Converts a 25x25 UI brightness array (0..255) into the Nothing Phone (3) IGlyphService
     * hardware brightness range (0..2047) into the provided `outBuffer` (or a new array).
     */
    fun toHardwareMatrixArray(uiFrame: IntArray, outBuffer: IntArray = IntArray(TOTAL_PIXELS)): IntArray {
        val limit = minOf(uiFrame.size, outBuffer.size, TOTAL_PIXELS)
        for (i in 0 until limit) {
            val b = uiFrame[i].coerceIn(0, 255)
            outBuffer[i] = (b * 2047) / 255
        }
        for (i in limit until outBuffer.size) {
            outBuffer[i] = 0
        }
        return outBuffer
    }
}
