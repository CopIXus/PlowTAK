package com.atakmap.android.plowtak.report

import android.util.Log
import com.atakmap.android.plowtak.cot.CotDetailAdapter
import com.atakmap.android.plowtak.cot.OutboundCotQueue
import com.atakmap.android.plowtak.cot.codec.HazardCotCodec
import com.atakmap.android.plowtak.model.HazardEvent
import com.atakmap.android.plowtak.model.HazardType
import com.atakmap.coremap.cot.event.CotDetail
import com.atakmap.coremap.cot.event.CotEvent
import com.atakmap.coremap.cot.event.CotPoint
import com.atakmap.coremap.maps.time.CoordinatedTime

/**
 * One-tap hazard drops. Builds a marker CoT at the current position with the
 * PlowTak hazard detail and dispatches it internally (local marker appears
 * immediately) and externally (fleet + observers see it).
 *
 * Photo attachments follow the ATAK attachment convention: the image lives
 * at `atak/attachments/<marker uid>/<photoFile>` and TAK attachment sync
 * carries it alongside the marker; the file *name* rides in the hazard
 * detail. SDK-fixup: capture flow — fire
 * `MediaStore.ACTION_IMAGE_CAPTURE` with EXTRA_OUTPUT pointed at that
 * attachment path, then re-send the hazard with [report] passing
 * `photoFile`. Verify the ImageDropDownReceiver / AttachmentManager surface
 * against the real 5.8 main.jar before wiring the capture button.
 */
class HazardReporter(
    private val queue: OutboundCotQueue
) {

    /** Drop a hazard at the given position. Returns the created event model. */
    fun report(
        type: HazardType,
        lat: Double,
        lon: Double,
        reporterUid: String,
        reporterCallsign: String,
        stormId: String,
        photoFile: String = ""
    ): HazardEvent {
        val now = System.currentTimeMillis()
        val hazard = HazardEvent(
            uid = "plowtak-hz-$reporterUid-$now",
            type = type,
            reporterUid = reporterUid,
            reporterCallsign = reporterCallsign,
            lat = lat,
            lon = lon,
            timeMs = now,
            stormId = stormId,
            photoFile = photoFile
        )

        try {
            val event = CotEvent()
            event.uid = hazard.uid
            event.type = type.cotType
            event.how = "h-g-i-g-o" // human, gps-derived, observed
            val time = CoordinatedTime()
            event.time = time
            event.start = time
            event.stale = time.addSeconds(HAZARD_STALE_S)
            event.setPoint(
                CotPoint(lat, lon, CotPoint.UNKNOWN, CotPoint.UNKNOWN, CotPoint.UNKNOWN)
            )

            val root = CotDetail("detail")
            val contact = CotDetail("contact")
            contact.setAttribute("callsign", "${type.label} (${reporterCallsign})")
            root.addChild(contact)
            val remarks = CotDetail("remarks")
            remarks.innerText = "${type.label} reported by $reporterCallsign"
            root.addChild(remarks)
            root.addChild(CotDetailAdapter.toCotDetail(HazardCotCodec.encode(hazard)))
            event.detail = root

            queue.send(event)
        } catch (e: Exception) {
            Log.e(TAG, "hazard drop failed", e)
        }
        return hazard
    }

    companion object {
        private const val TAG = "PlowTakHazard"

        /** Hazards live 8 h unless refreshed/deleted. */
        private const val HAZARD_STALE_S = 8 * 3600
    }
}
