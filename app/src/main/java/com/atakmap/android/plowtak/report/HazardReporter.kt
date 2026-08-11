package com.atakmap.android.plowtak.report

import android.util.Log
import com.atakmap.android.maps.MapView
import com.atakmap.android.plowtak.cot.CotDetailAdapter
import com.atakmap.android.plowtak.cot.OutboundCotQueue
import com.atakmap.android.plowtak.cot.codec.HazardCotCodec
import com.atakmap.android.plowtak.model.HazardEvent
import com.atakmap.android.plowtak.model.HazardType
import com.atakmap.android.plowtak.model.ReportLabels
import com.atakmap.coremap.cot.event.CotDetail
import com.atakmap.coremap.cot.event.CotEvent
import com.atakmap.coremap.cot.event.CotPoint
import com.atakmap.coremap.maps.time.CoordinatedTime

/**
 * One-tap hazard drops. Builds a marker CoT at the current position and
 * **broadcasts it to the TAK mesh** so peers and CloudTAK Map Items can see
 * it. The same event is also mirrored into Data Sync as
 * `{uid}-hazards.geojson` for late joiners / mission subscribers.
 *
 * Photo attachments follow the ATAK attachment convention: the image lives
 * at `atak/attachments/<marker uid>/<photoFile>` and TAK attachment sync
 * carries it alongside the marker; the file *name* rides in the hazard
 * detail. Capture uses ATAK QuickPic via [QuickPicHazardCapture] (long-press
 * a hazard button in the driver panel).
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
            contact.setAttribute(
                "callsign", ReportLabels.hazard(type.label, reporterCallsign)
            )
            root.addChild(contact)
            val remarks = CotDetail("remarks")
            remarks.innerText = "${type.label} reported by $reporterCallsign"
            root.addChild(remarks)
            root.addChild(CotDetailAdapter.toCotDetail(HazardCotCodec.encode(hazard)))
            event.detail = root

            // Broadcast so CloudTAK / peer ATAK without waiting on Data Sync.
            queue.send(event)
        } catch (e: Exception) {
            Log.e(TAG, "hazard drop failed", e)
        }
        return hazard
    }

    /** Show an already-known hazard on the local map (Data Sync pull). */
    fun showLocal(hazard: HazardEvent) {
        try {
            // Same UID already on the map (e.g. CoT arrived first) — do not
            // dispatch another CotEvent that could flicker or double-draw.
            val mapView = MapView.getMapView()
            if (mapView?.rootGroup?.deepFindUID(hazard.uid) != null) return

            val event = CotEvent()
            event.uid = hazard.uid
            event.type = hazard.type.cotType
            event.how = "h-g-i-g-o"
            val time = CoordinatedTime()
            event.time = time
            event.start = time
            event.stale = time.addSeconds(HAZARD_STALE_S)
            event.setPoint(
                CotPoint(
                    hazard.lat, hazard.lon,
                    CotPoint.UNKNOWN, CotPoint.UNKNOWN, CotPoint.UNKNOWN
                )
            )
            val root = CotDetail("detail")
            val contact = CotDetail("contact")
            contact.setAttribute(
                "callsign",
                ReportLabels.hazard(hazard.type.label, hazard.reporterCallsign)
            )
            root.addChild(contact)
            root.addChild(CotDetailAdapter.toCotDetail(HazardCotCodec.encode(hazard)))
            event.detail = root
            queue.sendLocalOnly(event)
        } catch (e: Exception) {
            Log.e(TAG, "hazard local show failed", e)
        }
    }

    companion object {
        private const val TAG = "PlowTakHazard"

        /** Hazards live 8 h unless refreshed/deleted. */
        private const val HAZARD_STALE_S = 8 * 3600
    }
}
