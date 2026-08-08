package com.atakmap.android.plowtak.report

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Shared formatting helpers for the records-grade exporters. */
internal object ExportFormat {

    /** ISO-8601 UTC, records-grade unambiguous timestamps. */
    fun iso(timeMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(timeMs))
    }

    fun num(v: Double, decimals: Int = 7): String =
        String.format(Locale.US, "%.${decimals}f", v)

    /** Minimal JSON string escaping (quotes, backslash, control chars). */
    fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** RFC-4180-ish CSV field: quoted when it contains comma/quote/newline. */
    fun csvField(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s

    fun csvRow(vararg fields: String): String = fields.joinToString(",") { csvField(it) }
}
