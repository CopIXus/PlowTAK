package com.atakmap.android.plowtak.cot.codec

/**
 * Framework-free stand-in for ATAK's CotDetail tree: element name, attributes,
 * children. All PlowTak detail building/parsing happens against this type so
 * codecs are unit-testable without the SDK; `cot/CotDetailAdapter` converts
 * to/from real CotDetail objects (never string XML templating).
 */
data class DetailNode(
    val name: String,
    val attributes: Map<String, String> = emptyMap(),
    val children: List<DetailNode> = emptyList()
) {
    fun attr(key: String): String? = attributes[key]

    fun attrBool(key: String, default: Boolean = false): Boolean =
        attributes[key]?.toBooleanStrictOrNull() ?: default

    fun attrDouble(key: String, default: Double = Double.NaN): Double =
        attributes[key]?.toDoubleOrNull() ?: default

    fun attrLong(key: String, default: Long = 0L): Long =
        attributes[key]?.toLongOrNull() ?: default

    fun firstChild(name: String): DetailNode? = children.firstOrNull { it.name == name }

    fun childrenNamed(name: String): List<DetailNode> = children.filter { it.name == name }

    companion object {
        /** The PlowTak detail namespace element inside CoT `<detail>`. */
        const val PLOWTAK = "__plowtak"
    }
}
