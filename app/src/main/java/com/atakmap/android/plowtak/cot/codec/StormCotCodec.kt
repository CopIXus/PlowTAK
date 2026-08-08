package com.atakmap.android.plowtak.cot.codec

import com.atakmap.android.plowtak.model.StormSession

/**
 * Detail codec for storm session start/end broadcasts so the whole fleet
 * converges on one session without a server-side authority.
 *
 * ```
 * <__plowtak>
 *   <storm id= start= end= startedBy=/>
 * </__plowtak>
 * ```
 */
object StormCotCodec {

    const val STORM_EVENT_TYPE = "b-i-x-plowtak-storm"

    fun encode(session: StormSession): DetailNode =
        DetailNode(
            DetailNode.PLOWTAK, emptyMap(),
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
        val plowtak = if (node.name == DetailNode.PLOWTAK) node
        else node.firstChild(DetailNode.PLOWTAK) ?: return null
        val storm = plowtak.firstChild("storm") ?: return null
        val id = storm.attr("id") ?: return null
        return StormSession(
            id = id,
            startTimeMs = storm.attrLong("start"),
            endTimeMs = storm.attrLong("end"),
            startedBy = storm.attr("startedBy") ?: ""
        )
    }
}
