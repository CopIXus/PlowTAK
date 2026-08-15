package com.atakmap.android.plowtak.ops

import com.atakmap.android.plowtak.coverage.CycleTimes
import com.atakmap.android.plowtak.model.Facility
import com.atakmap.android.plowtak.model.FacilityType
import com.atakmap.android.plowtak.model.SpecialZone
import com.atakmap.android.plowtak.model.VehicleCapability
import com.atakmap.android.plowtak.model.VehicleType
import com.atakmap.android.plowtak.model.ZoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningCodecTest {

    private fun fullProfile() = ProvisioningProfile(
        name = "City of X winter ops",
        agency = "City of X DPW",
        createdMs = 1_700_000_000_000L,
        capability = VehicleCapability.sanitize(
            VehicleCapability.defaultsFor(VehicleType.PLOW).copy(
                hasSalt = true,
                plowWidthM = 3.7,
                wingLeftWidthM = 4.9, wingRightWidthM = 4.9,
                callsign = "CTR-Plow",
                contractor = true
            )
        ),
        cycleTimes = CycleTimes(defaultMinutes = 45, p1Minutes = 30, p3Minutes = 90),
        coverageRetentionHours = 0.0,
        roadConditionTtlMinutes = 120,
        facilities = listOf(
            Facility("f1", "North Dome", FacilityType.SALT_DOME, 40.1, -83.1, 80.0)
        ),
        zones = listOf(
            SpecialZone(
                "z1", "High St bridge", ZoneType.BRIDGE, 0.5,
                40.2, -83.2, 120.0
            ),
            SpecialZone(
                "z2", "School block", ZoneType.SCHOOL, 0.5,
                40.3, -83.3, 0.0,
                polygon = listOf(40.3 to -83.3, 40.31 to -83.3, 40.31 to -83.29)
            )
        )
    )

    @Test
    fun `full profile round-trips`() {
        val orig = fullProfile()
        val back = ProvisioningCodec.decode(ProvisioningCodec.encode(orig))!!

        assertEquals(orig.name, back.name)
        assertEquals(orig.agency, back.agency)
        assertEquals(orig.createdMs, back.createdMs)
        assertEquals(orig.capability, back.capability)
        assertEquals(orig.cycleTimes, back.cycleTimes)
        assertEquals(orig.coverageRetentionHours, back.coverageRetentionHours)
        assertEquals(orig.roadConditionTtlMinutes, back.roadConditionTtlMinutes)
        assertEquals(orig.facilities, back.facilities)
        assertEquals(orig.zones, back.zones)
    }

    @Test
    fun `contractor onboarding profile carries the flag`() {
        val back = ProvisioningCodec.decode(ProvisioningCodec.encode(fullProfile()))!!
        assertTrue(back.capability!!.contractor)
        assertTrue(back.capability!!.canTreat)
    }

    @Test
    fun `sections are optional`() {
        val cyclesOnly = ProvisioningProfile(cycleTimes = CycleTimes(defaultMinutes = 30))
        val back = ProvisioningCodec.decode(ProvisioningCodec.encode(cyclesOnly))!!
        assertNull(back.capability)
        assertEquals(30, back.cycleTimes!!.defaultMinutes)
        assertTrue(back.facilities.isEmpty())
        assertTrue(back.zones.isEmpty())
    }

    @Test
    fun `retention and condition ttl round-trip`() {
        val p = ProvisioningProfile(
            cycleTimes = CycleTimes(defaultMinutes = 40),
            coverageRetentionHours = 0.0,
            roadConditionTtlMinutes = 90
        )
        val back = ProvisioningCodec.decode(ProvisioningCodec.encode(p))!!
        assertEquals(0.0, back.coverageRetentionHours!!, 0.0)
        assertEquals(90, back.roadConditionTtlMinutes)
        assertEquals(40, back.cycleTimes!!.defaultMinutes)
    }

    @Test
    fun `hand-written minimal json decodes`() {
        val json = """
            {
              "format": "plowtak-provisioning",
              "version": 1,
              "cycleTimes": { "default": 40 },
              "facilities": [
                { "id": "f1", "name": "Dome", "type": "salt_dome",
                  "lat": 40.1, "lon": -83.1, "radiusM": 75 }
              ]
            }
        """.trimIndent()
        val p = ProvisioningCodec.decode(json)!!
        assertEquals(40, p.cycleTimes!!.defaultMinutes)
        assertEquals(1, p.facilities.size)
        assertEquals(FacilityType.SALT_DOME, p.facilities[0].type)
    }

    @Test
    fun `illegal capability combos are sanitized on decode`() {
        val json = """
            {
              "format": "plowtak-provisioning",
              "version": 1,
              "capability": { "type": "observer", "hasBlade": true, "contractor": true }
            }
        """.trimIndent()
        val cap = ProvisioningCodec.decode(json)!!.capability!!
        assertTrue(!cap.hasBlade)      // observers have no blade
        assertTrue(!cap.contractor)    // only treat-capable units
    }

    @Test
    fun `malformed list entries are skipped not fatal`() {
        val json = """
            {
              "format": "plowtak-provisioning",
              "version": 1,
              "facilities": [
                { "id": "ok", "name": "Dome", "type": "salt_dome", "lat": 40, "lon": -83 },
                { "name": "missing id and type" },
                "not even an object"
              ],
              "zones": [ { "id": "bad", "type": "volcano", "lat": 1, "lon": 2 } ]
            }
        """.trimIndent()
        val p = ProvisioningCodec.decode(json)!!
        assertEquals(listOf("ok"), p.facilities.map { it.id })
        assertTrue(p.zones.isEmpty())
    }

    @Test
    fun `unrelated or unsupported json is rejected`() {
        assertNull(ProvisioningCodec.decode("{}"))
        assertNull(ProvisioningCodec.decode("""{"format":"geojson","version":1}"""))
        assertNull(
            ProvisioningCodec.decode("""{"format":"plowtak-provisioning","version":99}""")
        )
        assertNull(ProvisioningCodec.decode("not json"))
    }
}
