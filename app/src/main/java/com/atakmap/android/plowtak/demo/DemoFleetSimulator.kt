package com.atakmap.android.plowtak.demo

import android.util.Log
import com.atakmap.android.plowtak.cot.CotDetailAdapter
import com.atakmap.android.plowtak.cot.OutboundCotQueue
import com.atakmap.android.plowtak.cot.PlowCotPublisher
import com.atakmap.android.plowtak.cot.codec.CoverageCotCodec
import com.atakmap.android.plowtak.cot.codec.PlowTakDetail
import com.atakmap.android.plowtak.coverage.CoverageStore
import com.atakmap.android.plowtak.coverage.GeoMath
import com.atakmap.android.plowtak.model.HazardEvent
import com.atakmap.android.plowtak.model.HazardType
import com.atakmap.android.plowtak.model.Material
import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.PlowVehicle
import com.atakmap.android.plowtak.model.TrackPoint
import com.atakmap.android.plowtak.model.TreatSegment
import com.atakmap.android.plowtak.model.VehicleStatus
import com.atakmap.android.plowtak.model.VehicleType
import com.atakmap.android.plowtak.model.WidthPreset
import com.atakmap.android.plowtak.ops.FleetManager
import com.atakmap.android.plowtak.report.HazardReporter
import com.atakmap.coremap.cot.event.CotDetail
import com.atakmap.coremap.cot.event.CotEvent
import com.atakmap.coremap.cot.event.CotPoint
import com.atakmap.coremap.maps.time.CoordinatedTime
import java.io.File
import java.util.Random
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

/**
 * Showcase fleet: ~30 synthetic plow units near the operator.
 * Uses GraphHopper road packs when present (VNS `tools/VNS/GH` or a custom
 * road-snap folder); otherwise geodesic motion — VNS is optional.
 * Local visibility is via [FleetManager] markers; CoT goes external for peers.
 */
class DemoFleetSimulator(
    private val queue: OutboundCotQueue,
    private val fleetManager: FleetManager,
    private val coverageStore: CoverageStore,
    private val hazardReporter: HazardReporter,
    private val stormIdProvider: () -> String,
    private val anchorProvider: () -> Pair<Double, Double>?,
    private val packDirResolver: () -> File?,
    private val scheduler: () -> ScheduledExecutorService?,
    private val onHazard: ((HazardEvent) -> Unit)? = null,
    private val random: Random = Random()
) {

    @Volatile
    private var running = false
    private var tickFuture: ScheduledFuture<*>? = null
    private val units = ArrayList<DemoUnit>()
    private var walker: DemoRoadWalker? = null

    val isRunning: Boolean get() = running

    data class StartResult(
        val ok: Boolean,
        val message: String,
        val unitCount: Int = 0,
        val onRoads: Boolean = false
    )

    /** Start (or no-op if already running). Heavy pack open runs on caller thread. */
    @Synchronized
    fun start(unitCount: Int = DEFAULT_UNIT_COUNT): StartResult {
        if (running) {
            return StartResult(true, "Demo fleet already running (${units.size} units)", units.size, walker != null)
        }
        val anchor = anchorProvider()
            ?: return StartResult(false, "No GPS fix yet — wait for your position, then try again")
        val packDir = packDirResolver()
        walker = packDir?.let { DemoRoadWalker.openOrNull(it) }
        if (walker == null) {
            Log.w(TAG, "no GraphHopper pack; demo trucks will use geodesic fallback")
        }

        units.clear()
        val now = System.currentTimeMillis()
        for (i in 1..unitCount) {
            val unit = spawnUnit(i, anchor.first, anchor.second, now)
            units.add(unit)
            publishPli(unit, now)
            fleetManager.update(toVehicle(unit, now))
        }

        running = true
        val exec = scheduler()
        tickFuture = exec?.scheduleWithFixedDelay(
            { safeTick() },
            TICK_MS, TICK_MS, TimeUnit.MILLISECONDS
        )
        val onRoads = walker != null
        val msg = if (onRoads) {
            "Demo fleet started: $unitCount units on roads (within ${RADIUS_MI.toInt()} mi)"
        } else {
            "Demo fleet started: $unitCount units near map (geodesic — no road pack). " +
                "Optional: set a GraphHopper pack folder in Settings for on-road driving."
        }
        Log.i(TAG, msg)
        return StartResult(true, msg, unitCount, onRoads)
    }

    @Synchronized
    fun stop(): String {
        if (!running) return "Demo fleet is not running"
        running = false
        tickFuture?.cancel(false)
        tickFuture = null
        val snapshot = units.toList()
        val uids = snapshot.map { it.uid }
        // Do not flush long-lived coverage on stop — that keeps CoTs on
        // CloudTAK/ATAK for hours. Drop local demo paint and tombstone PLI.
        for (u in snapshot) {
            u.track.clear()
            publishPliTombstone(u)
        }
        units.clear()
        walker = null
        fleetManager.removeAll(uids)
        try {
            coverageStore.removeByVehicleUids(uids)
        } catch (e: Exception) {
            Log.w(TAG, "demo coverage cleanup failed", e)
        }
        Log.i(TAG, "demo fleet stopped (${uids.size} units tombstoned)")
        return "Demo fleet stopped (${uids.size} units cleared; markers stale in ~${TOMBSTONE_STALE_S}s)"
    }

    private fun safeTick() {
        try {
            tick()
        } catch (e: Exception) {
            Log.e(TAG, "demo tick failed", e)
        }
    }

    @Synchronized
    private fun tick() {
        if (!running) return
        val now = System.currentTimeMillis()
        val stormId = stormIdProvider()
        val anchor = anchorProvider()
        val road = walker

        for (u in units) {
            maybeChangeBehavior(u, now)
            advanceUnit(u, road, now)
            // Keep demo trucks near the operator / map center.
            if (anchor != null) {
                val d = GeoMath.distanceMeters(anchor.first, anchor.second, u.lat, u.lon)
                val maxR = if (road != null) RADIUS_M * 1.15 else NEAR_SPAWN_M * 1.25
                if (d > maxR) {
                    reseedsNear(u, anchor.first, anchor.second, now)
                }
            }
            u.stormId = stormId
            publishPli(u, now)
            fleetManager.update(toVehicle(u, now))
            if (u.isTreating) {
                appendCoveragePoint(u, now)
                if (u.track.size >= COVERAGE_FLUSH_POINTS ||
                    now - u.trackStartMs >= COVERAGE_FLUSH_MS
                ) {
                    flushCoverage(u, now)
                }
            } else if (u.track.size >= 2) {
                flushCoverage(u, now)
            }
            maybeDropHazard(u, now)
        }
        queue.onTick()
    }

    private fun spawnUnit(index: Int, anchorLat: Double, anchorLon: Double, now: Long): DemoUnit {
        val callsign = "DemoPlow-$index"
        val uid = "$UID_PREFIX$index"
        val profile = randomProfile()
        val road = walker
        val pose = road?.seed(anchorLat, anchorLon, RADIUS_M)
        val (lat, lon, heading) = if (pose != null) {
            Triple(pose.lat, pose.lon, pose.headingDeg)
        } else {
            // Bias nearer the anchor so units are visible without zooming out
            // (uniform-in-radius put most trucks many miles away).
            val bearing = random.nextDouble() * 360.0
            val dist = NEAR_SPAWN_M * Math.sqrt(random.nextDouble())
            val p = offset(anchorLat, anchorLon, bearing, dist)
            Triple(p.first, p.second, bearing)
        }
        val unit = DemoUnit(
            index = index,
            uid = uid,
            callsign = callsign,
            type = profile.type,
            hasBlade = profile.hasBlade,
            hasSalt = profile.hasSalt,
            plowWidthM = profile.widthM,
            lat = lat,
            lon = lon,
            headingDeg = heading,
            speedMps = profile.speedMps,
            pose = pose,
            status = VehicleStatus.TREATING,
            bladeDown = profile.hasBlade,
            saltOn = profile.hasSalt && random.nextBoolean(),
            material = Material.entries[random.nextInt(Material.entries.size)],
            nextBehaviorMs = now + behaviorHoldMs(),
            nextHazardMs = now + hazardDelayMs(),
            stormId = stormIdProvider()
        )
        applyStatusEquipment(unit)
        return unit
    }

    private fun reseedsNear(u: DemoUnit, lat: Double, lon: Double, now: Long) {
        val pose = walker?.seed(lat, lon, RADIUS_M * 0.7)
        if (pose != null) {
            u.pose = pose
            u.lat = pose.lat
            u.lon = pose.lon
            u.headingDeg = pose.headingDeg
        } else {
            val bearing = random.nextDouble() * 360.0
            val dist = NEAR_SPAWN_M * Math.sqrt(random.nextDouble())
            val p = offset(lat, lon, bearing, dist)
            u.lat = p.first
            u.lon = p.second
            u.headingDeg = bearing
            u.pose = null
        }
        flushCoverage(u, now)
    }

    private fun advanceUnit(u: DemoUnit, road: DemoRoadWalker?, now: Long) {
        if (u.status == VehicleStatus.LOADING ||
            u.status == VehicleStatus.REFUELING ||
            u.status == VehicleStatus.ON_BREAK ||
            u.status == VehicleStatus.OUT_OF_SERVICE
        ) {
            // Parked at dome / yard — no motion.
            return
        }
        val stepM = u.speedMps * (TICK_MS / 1000.0)
        val pose = u.pose
        if (road != null && pose != null) {
            val next = road.advance(pose, stepM)
            u.pose = next
            u.lat = next.lat
            u.lon = next.lon
            u.headingDeg = next.headingDeg
        } else {
            // Geodesic crawl when no road pack.
            val br = Math.toRadians(u.headingDeg)
            u.lat += (stepM * cos(br)) / 111_320.0
            u.lon += (stepM * sin(br)) /
                (111_320.0 * cos(Math.toRadians(u.lat)).coerceAtLeast(0.2))
            if (random.nextDouble() < 0.08) {
                u.headingDeg = (u.headingDeg + (random.nextDouble() - 0.5) * 40.0 + 360.0) % 360.0
            }
        }
    }

    private fun maybeChangeBehavior(u: DemoUnit, now: Long) {
        if (now < u.nextBehaviorMs) return
        u.nextBehaviorMs = now + behaviorHoldMs()
        val roll = random.nextDouble()
        u.status = when {
            roll < 0.55 -> VehicleStatus.TREATING
            roll < 0.70 -> VehicleStatus.DEADHEAD
            roll < 0.82 -> VehicleStatus.LOADING
            roll < 0.90 -> VehicleStatus.REFUELING
            roll < 0.96 -> VehicleStatus.ON_BREAK
            else -> VehicleStatus.OUT_OF_SERVICE
        }
        applyStatusEquipment(u)
        // Occasional speed change while rolling.
        if (u.status == VehicleStatus.TREATING || u.status == VehicleStatus.DEADHEAD) {
            u.speedMps = (12.0 + random.nextDouble() * 18.0) // ~27–67 mph range clipped below
                .coerceIn(6.0, 22.0) // plow speeds ~13–50 mph
        }
    }

    private fun applyStatusEquipment(u: DemoUnit) {
        when (u.status) {
            VehicleStatus.TREATING -> {
                when {
                    u.type == VehicleType.SALT_ONLY -> {
                        u.bladeDown = false
                        u.saltOn = true
                    }
                    u.hasBlade && u.hasSalt -> {
                        val mode = random.nextInt(3)
                        u.bladeDown = mode != 1 // 0 plow+salt, 1 salt only, 2 plow only
                        u.saltOn = mode != 2
                    }
                    u.hasBlade -> {
                        u.bladeDown = true
                        u.saltOn = false
                    }
                    else -> {
                        u.bladeDown = false
                        u.saltOn = u.hasSalt
                    }
                }
                if (u.saltOn) {
                    u.material = Material.entries[random.nextInt(Material.entries.size)]
                }
            }
            VehicleStatus.DEADHEAD -> {
                u.bladeDown = false
                u.saltOn = false
            }
            else -> {
                u.bladeDown = false
                u.saltOn = false
            }
        }
    }

    private fun appendCoveragePoint(u: DemoUnit, now: Long) {
        if (u.track.isEmpty()) u.trackStartMs = now
        u.track.add(TrackPoint(u.lat, u.lon, now, u.headingDeg))
    }

    private fun flushCoverage(u: DemoUnit, now: Long) {
        if (u.track.size < 2) {
            u.track.clear()
            return
        }
        val mode = when {
            u.bladeDown && u.saltOn -> MaterialMode.PLOW_AND_SALT
            u.bladeDown -> MaterialMode.PLOW_ONLY
            u.saltOn -> MaterialMode.SALT
            else -> MaterialMode.NONE
        }
        if (mode == MaterialMode.NONE) {
            u.track.clear()
            return
        }
        val segment = TreatSegment(
            id = TreatSegment.makeId(u.uid, u.trackStartMs),
            vehicleUid = u.uid,
            callsign = u.callsign,
            stormId = u.stormId,
            operatorId = "demo",
            material = mode,
            widthM = u.plowWidthM,
            points = u.track.toList(),
            startTimeMs = u.trackStartMs,
            endTimeMs = u.track.last().timeMs,
            spreadMaterial = if (u.saltOn) u.material else null
        )
        u.track.clear()
        try {
            coverageStore.addLocal(segment)
            publishCoverage(segment)
        } catch (e: Exception) {
            Log.w(TAG, "demo coverage publish failed for ${u.callsign}", e)
        }
    }

    private fun maybeDropHazard(u: DemoUnit, now: Long) {
        if (now < u.nextHazardMs) return
        u.nextHazardMs = now + hazardDelayMs()
        // Only a subset of units drop hazards, and not while loading.
        if (u.status == VehicleStatus.LOADING || u.status == VehicleStatus.ON_BREAK) return
        if (random.nextDouble() > 0.35) return
        val type = HazardType.entries[random.nextInt(HazardType.entries.size)]
        try {
            val hazard = hazardReporter.report(
                type = type,
                lat = u.lat,
                lon = u.lon,
                reporterUid = u.uid,
                reporterCallsign = u.callsign,
                stormId = u.stormId
            )
            onHazard?.invoke(hazard)
            Log.d(TAG, "${u.callsign} dropped ${type.label} (${hazard.uid})")
        } catch (e: Exception) {
            Log.w(TAG, "demo hazard failed", e)
        }
    }

    private fun publishPli(u: DemoUnit, now: Long) {
        queue.send(buildPliEvent(u, staleSeconds = PLI_STALE_S), alsoInternal = true)
    }

    /** Same UID with near-immediate stale so ATAK + CloudTAK drop the contact. */
    private fun publishPliTombstone(u: DemoUnit) {
        queue.send(buildPliEvent(u, staleSeconds = TOMBSTONE_STALE_S), alsoInternal = true)
    }

    private fun buildPliEvent(u: DemoUnit, staleSeconds: Int): CotEvent {
        val detail = PlowTakDetail(
            vehicleType = u.type,
            hasBlade = u.hasBlade,
            hasSalt = u.hasSalt,
            canTreat = u.hasBlade || u.hasSalt,
            status = u.status,
            bladeDown = u.bladeDown,
            saltOn = u.saltOn,
            material = u.material,
            plowWidthM = u.plowWidthM,
            headingDeg = u.headingDeg,
            stormId = u.stormId,
            operatorId = "demo",
            operatorName = u.callsign,
            widthPreset = WidthPreset.STANDARD,
            reloadCount = if (u.status == VehicleStatus.LOADING) random.nextInt(4) else 0
        )
        val event = CotEvent()
        event.uid = u.uid
        event.type = PlowCotPublisher.PLI_EVENT_TYPE
        event.how = "m-g"
        val time = CoordinatedTime()
        event.time = time
        event.start = time
        event.stale = time.addSeconds(staleSeconds.coerceAtLeast(1))
        event.setPoint(
            CotPoint(u.lat, u.lon, CotPoint.UNKNOWN, CotPoint.UNKNOWN, CotPoint.UNKNOWN)
        )
        val root = CotDetail("detail")
        val contact = CotDetail("contact")
        contact.setAttribute("callsign", u.callsign)
        root.addChild(contact)
        root.addChild(CotDetailAdapter.toCotDetail(detail.toNode()))
        event.detail = root
        return event
    }

    private fun publishCoverage(segment: TreatSegment) {
        val node = CoverageCotCodec.encode(segment.stormId, listOf(segment))
        val first = segment.points.first()
        val event = CotEvent()
        event.uid = "${segment.vehicleUid}-cov-${segment.startTimeMs}"
        event.type = CoverageCotCodec.COVERAGE_EVENT_TYPE
        event.how = "m-g"
        val time = CoordinatedTime()
        event.time = time
        event.start = time
        // Demo paint should not linger for hours after stop / network lag.
        event.stale = time.addSeconds(DEMO_COVERAGE_STALE_S)
        event.setPoint(
            CotPoint(first.lat, first.lon, CotPoint.UNKNOWN, CotPoint.UNKNOWN, CotPoint.UNKNOWN)
        )
        val root = CotDetail("detail")
        root.addChild(CotDetailAdapter.toCotDetail(node))
        event.detail = root
        // Local overlay already has addLocal; internal echo would re-merge.
        queue.send(event, alsoInternal = false)
    }

    private fun toVehicle(u: DemoUnit, now: Long) = PlowVehicle(
        uid = u.uid,
        callsign = u.callsign,
        type = u.type,
        status = u.status,
        lat = u.lat,
        lon = u.lon,
        headingDeg = u.headingDeg,
        lastUpdateMs = now,
        hasBlade = u.hasBlade,
        hasSalt = u.hasSalt,
        bladeDown = u.bladeDown,
        saltOn = u.saltOn,
        stormId = u.stormId,
        operatorId = "demo",
        operatorName = u.callsign
    )

    private fun randomProfile(): UnitProfile {
        val roll = random.nextDouble()
        return when {
            roll < 0.70 -> UnitProfile(
                VehicleType.PLOW, hasBlade = true, hasSalt = true, widthM = 3.0,
                speedMps = 8.0 + random.nextDouble() * 8.0
            )
            roll < 0.90 -> UnitProfile(
                VehicleType.SALT_ONLY, hasBlade = false, hasSalt = true, widthM = 2.4,
                speedMps = 10.0 + random.nextDouble() * 8.0
            )
            else -> UnitProfile(
                VehicleType.PLOW, hasBlade = true, hasSalt = false, widthM = 3.7,
                speedMps = 7.0 + random.nextDouble() * 6.0
            )
        }
    }

    private fun behaviorHoldMs(): Long =
        (BEHAVIOR_MIN_MS + random.nextInt((BEHAVIOR_MAX_MS - BEHAVIOR_MIN_MS).toInt())).toLong()

    private fun hazardDelayMs(): Long =
        (HAZARD_MIN_MS + random.nextInt((HAZARD_MAX_MS - HAZARD_MIN_MS).toInt())).toLong()

    private class DemoUnit(
        val index: Int,
        val uid: String,
        val callsign: String,
        val type: VehicleType,
        val hasBlade: Boolean,
        val hasSalt: Boolean,
        val plowWidthM: Double,
        var lat: Double,
        var lon: Double,
        var headingDeg: Double,
        var speedMps: Double,
        var pose: DemoRoadWalker.Pose?,
        var status: VehicleStatus,
        var bladeDown: Boolean,
        var saltOn: Boolean,
        var material: Material,
        var nextBehaviorMs: Long,
        var nextHazardMs: Long,
        var stormId: String,
        val track: MutableList<TrackPoint> = ArrayList(),
        var trackStartMs: Long = 0L
    ) {
        val isTreating: Boolean
            get() = status == VehicleStatus.TREATING && (bladeDown || saltOn)
    }

    private data class UnitProfile(
        val type: VehicleType,
        val hasBlade: Boolean,
        val hasSalt: Boolean,
        val widthM: Double,
        val speedMps: Double
    )

    companion object {
        private const val TAG = "PlowTakDemo"
        const val DEFAULT_UNIT_COUNT = 30
        const val RADIUS_MI = 20.0
        private val RADIUS_M = RADIUS_MI * 1609.344
        /** Geodesic spawn cluster (~3 mi) so Fold demos see markers immediately. */
        private val NEAR_SPAWN_M = 3.0 * 1609.344
        const val UID_PREFIX = "plowtak-demo-"

        private const val TICK_MS = 2_000L
        /** Live demo PLI lifetime — short so stop tombstones win quickly. */
        private const val PLI_STALE_S = 30
        /** Stop tombstone: ATAK/CloudTAK drop the contact within a few seconds. */
        private const val TOMBSTONE_STALE_S = 3
        /** Demo coverage CoT — minutes, not hours (real ops still use long retention). */
        private const val DEMO_COVERAGE_STALE_S = 120
        private const val COVERAGE_FLUSH_POINTS = 12
        private const val COVERAGE_FLUSH_MS = 25_000L
        private const val BEHAVIOR_MIN_MS = 45_000L
        private const val BEHAVIOR_MAX_MS = 180_000L
        private const val HAZARD_MIN_MS = 90_000L
        private const val HAZARD_MAX_MS = 300_000L

        private fun offset(
            lat: Double, lon: Double, bearingDeg: Double, distM: Double
        ): Pair<Double, Double> {
            val br = Math.toRadians(bearingDeg)
            val dLat = (distM * cos(br)) / 111_320.0
            val dLon = (distM * sin(br)) /
                (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
            return (lat + dLat) to (lon + dLon)
        }
    }
}
