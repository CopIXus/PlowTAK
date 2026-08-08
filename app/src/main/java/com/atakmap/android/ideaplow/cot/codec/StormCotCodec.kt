package com.atakmap.android.ideaplow.cot.codec

import com.atakmap.android.ideaplow.model.StormSession

/**
 * Detail codec for storm session start/end broadcasts so the whole fleet
 * converges on one session without a server-side authority.
 *
 * ```
 * <__ideaplow>
 *   <storm id= start= end= startedBy=/>
 * </__ideaplow>
 * ```
 */
object StormCotCodec {

    const val STORM_EVENT_TYPE = "b-i-x-ideaplow-storm"

    fun encode(session: StormSession): DetailNode =
        DetailNode(
            DetailNode.IDEAPLOW, emptyMap(),
            listOf(
                DetailNode(
                    "storm", mapOf(
                        "id" to session.id,
                        "start" to session.startTimeMs.toString(),
                        "end" to session.endTimeMs.toString(),
                        "startedBy" to session.startedBy
                    )
                )
            )
        )

    fun decode(node: DetailNode): StormSession? {
        val ideaplow = if (node.name == DetailNode.IDEAPLOW) node
        else node.firstChild(DetailNode.IDEAPLOW) ?: return null
        val storm = ideaplow.firstChild("storm") ?: return null
        val id = storm.attr("id") ?: return null
        return StormSession(
            id = id,
            startTimeMs = storm.attrLong("start"),
            endTimeMs = storm.attrLong("end"),
            startedBy = storm.attr("startedBy") ?: ""
        )
    }
}
