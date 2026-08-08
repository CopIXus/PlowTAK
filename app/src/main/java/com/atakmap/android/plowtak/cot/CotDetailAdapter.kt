package com.atakmap.android.plowtak.cot

import com.atakmap.android.plowtak.cot.codec.DetailNode
import com.atakmap.coremap.cot.event.CotDetail

/**
 * Bridges the framework-free [DetailNode] tree to ATAK's CotDetail. This is
 * the ONLY place PlowTak detail structures touch the SDK type — codecs stay
 * unit-testable and CoT is always built via the CotDetail API, never string
 * XML templating.
 */
object CotDetailAdapter {

    /** Recursively convert a [DetailNode] into a CotDetail element. */
    fun toCotDetail(node: DetailNode): CotDetail {
        val detail = CotDetail(node.name)
        for ((k, v) in node.attributes) {
            detail.setAttribute(k, v)
        }
        for (child in node.children) {
            detail.addChild(toCotDetail(child))
        }
        return detail
    }

    /** Recursively convert a CotDetail element into a [DetailNode]. */
    fun fromCotDetail(detail: CotDetail): DetailNode {
        val attrs = mutableMapOf<String, String>()
        val cotAttrs = detail.attributes
        if (cotAttrs != null) {
            for (attr in cotAttrs) {
                attrs[attr.name] = attr.value
            }
        }
        val children = mutableListOf<DetailNode>()
        for (i in 0 until detail.childCount()) {
            val child = detail.getChild(i) ?: continue
            children.add(fromCotDetail(child))
        }
        return DetailNode(detail.elementName, attrs, children)
    }

    /** Find the `<__plowtak>` child of a CoT event's detail, if present. */
    fun findPlowTakNode(eventDetail: CotDetail?): DetailNode? {
        val detail = eventDetail ?: return null
        val child = detail.getFirstChildByName(0, DetailNode.PLOWTAK) ?: return null
        return fromCotDetail(child)
    }
}
