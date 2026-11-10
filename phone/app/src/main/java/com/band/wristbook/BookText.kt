package com.band.wristbook

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object BookText {
    private val WINDOWS_1251: Charset = Charset.forName("windows-1251")
    private val FORBIDDEN_NAME_CHARS = Regex("[\\\\/:*?\"<>|+,;=\\[\\]\\p{Cntrl}]")
    private val BLANK_LINES = Regex("\n{3,}")
    private const val EXTENSION = ".txt"
    private const val MAX_STEM_LENGTH = 80
    private const val BYTE_MASK = 0xFF
    private const val BOM_FIRST = 0xFF
    private const val BOM_SECOND = 0xFE

    fun decode(bytes: ByteArray): String {
        val text = when {
            bytes.startsWith(BOM_FIRST, BOM_SECOND) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.startsWith(BOM_SECOND, BOM_FIRST) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> decodeUtf8OrLegacy(bytes)
        }
        return normalize(text)
    }

    fun toFileName(title: String, fallback: String): String {
        val trimmed = title.trim()
        val withoutExtension = if (trimmed.endsWith(EXTENSION, ignoreCase = true)) {
            trimmed.dropLast(EXTENSION.length)
        } else {
            trimmed
        }
        val stem = FORBIDDEN_NAME_CHARS.replace(withoutExtension, "_")
            .trim()
            .trimStart('.')
            .take(MAX_STEM_LENGTH)
            .ifBlank { fallback }
        return stem + EXTENSION
    }

    private fun decodeUtf8OrLegacy(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, WINDOWS_1251)
        }
    }

    fun normalize(raw: String): String {
        val builder = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val current = raw[index]
            when {
                current == '\r' -> {
                    builder.append('\n')
                    if (index + 1 < raw.length && raw[index + 1] == '\n') index++
                }
                current == '\n' || current == '\t' -> builder.append(current)
                current == '\u00A0' -> builder.append(' ')
                Character.isHighSurrogate(current) -> {
                    if (index + 1 < raw.length && Character.isLowSurrogate(raw[index + 1])) {
                        builder.append(current).append(raw[index + 1])
                        index++
                    }
                }
                Character.isLowSurrogate(current) -> Unit
                current == '\uFEFF' || current == '\u200B' || current.isISOControl() -> Unit
                else -> builder.append(current)
            }
            index++
        }
        return BLANK_LINES.replace(builder.toString(), "\n\n").trim()
    }

    private fun ByteArray.startsWith(first: Int, second: Int): Boolean =
        size >= 2 && (this[0].toInt() and BYTE_MASK) == first && (this[1].toInt() and BYTE_MASK) == second
}
