package com.example.composetutorial.serial

object HexUtils {

    /** "01 03 00 0A" 或 "0103000A" -> ByteArray;格式非法返回 null */
    fun parse(text: String): ByteArray? {
        val clean = text.filter { !it.isWhitespace() }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) {
                clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** ByteArray -> "01 03 00 0A" */
    fun format(data: ByteArray): String =
        data.joinToString(" ") { "%02X".format(it) }
}
