package com.atakmap.android.ideaplow.gis

import com.atakmap.android.ideaplow.model.RoutePriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadNetworkImporterTest {

    private fun lineFeature(
        props: String,
        coords: String = "[[-86.0, 36.0], [-86.0, 36.01]]",
        id: String? = null
    ): String {
        val idAttr = if (id != null) "\"id\": \"$id\"," else ""
        return """{"type": "Feature", $idAttr "properties": $props,
            "geometry": {"type": "LineString", "coordinates": $coords}}"""
    }

    private fun collection(vararg features: String) =
        """{"type": "FeatureCollection", "features": [${features.joinToString(",")}]}"""

    @Test
    fun importsAttributedLineString() {
        val result = RoadNetworkImporter.import(
            collection(
                lineFeature(
                    """{"lanes": 4, "priority": "p1", "route_id": "R-9",
                       "oneway": false, "name": "Main St"}""",
                    id = "main-1"
                )
            )
        )
        assertTrue(result.ok)
        assertEquals(1, result.imported)
        assertEquals(0, result.skipped)
        val road = result.network.roads.single()
        assertEquals("main-1", road.id)
        assertEquals("Main St", road.name)
        assertEquals(4, road.lanes)
        assertEquals(RoutePriority.P1, road.priority)
        assertEquals("R-9", road.routeId)
        assertFalse(road.oneway)
        assertEquals(2, road.points.size)
        assertEquals(36.0, road.points[0].lat, 1e-9)
        assertEquals(-86.0, road.points[0].lon, 1e-9)
    }

    @Test
    fun multiLineStringSplitsIntoParts() {
        val result = RoadNetworkImporter.import(
            """{"type": "FeatureCollection", "features": [
                {"type": "Feature", "id": "m", "properties": {"lanes": 2},
                 "geometry": {"type": "MultiLineString", "coordinates": [
                   [[-86.0, 36.0], [-86.0, 36.01]],
                   [[-86.1, 36.0], [-86.1, 36.01]]
                 ]}}]}"""
        )
        assertEquals(2, result.imported)
        assertEquals(setOf("m-0", "m-1"), result.network.roads.map { it.id }.toSet())
        assertTrue(result.network.roads.all { it.lanes == 2 })
    }

    @Test
    fun missingPropertiesGetNeutralDefaults() {
        val result = RoadNetworkImporter.import(collection(lineFeature("{}")))
        val road = result.network.roads.single()
        assertEquals(0, road.lanes)
        assertNull(road.priority)
        assertEquals("", road.routeId)
        assertFalse(road.oneway)
    }

    @Test
    fun propertyAliasesAndCaseInsensitivity() {
        val result = RoadNetworkImporter.import(
            collection(
                lineFeature(
                    """{"LANE_COUNT": 3, "Route": "P7", "ONE_WAY": "yes",
                       "road_name": "Elm"}"""
                )
            )
        )
        val road = result.network.roads.single()
        assertEquals(3, road.lanes)
        assertEquals("P7", road.routeId)
        assertTrue(road.oneway)
        assertEquals("Elm", road.name)
    }

    @Test
    fun priorityParsingVariants() {
        assertEquals(RoutePriority.P1, RoadNetworkImporter.parsePriority("p1"))
        assertEquals(RoutePriority.P2, RoadNetworkImporter.parsePriority("P2"))
        assertEquals(RoutePriority.P3, RoadNetworkImporter.parsePriority(3.0))
        assertEquals(RoutePriority.P1, RoadNetworkImporter.parsePriority("Priority 1"))
        assertNull(RoadNetworkImporter.parsePriority("residential"))
        assertNull(RoadNetworkImporter.parsePriority(null))
    }

    @Test
    fun skipsBadFeaturesKeepsGood() {
        val result = RoadNetworkImporter.import(
            collection(
                lineFeature("""{"lanes": 2}"""),
                // Point geometry: not a road.
                """{"type": "Feature", "properties": {},
                    "geometry": {"type": "Point", "coordinates": [-86.0, 36.0]}}""",
                // Single-point line: unusable.
                lineFeature("{}", coords = "[[-86.0, 36.0]]")
            )
        )
        assertEquals(1, result.imported)
        assertTrue(result.skipped >= 1)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun rejectsInvalidCoordinates() {
        val result = RoadNetworkImporter.import(
            collection(lineFeature("{}", coords = "[[-186.0, 36.0], [-186.0, 36.01]]"))
        )
        assertEquals(0, result.imported)
    }

    @Test
    fun invalidJsonFailsSoft() {
        val result = RoadNetworkImporter.import("{ not json")
        assertFalse(result.ok)
        assertTrue(result.network.isEmpty())
    }

    @Test
    fun unrecognizedFormatFailsSoft() {
        val result = RoadNetworkImporter.import("id,lanes\n1,2")
        assertFalse(result.ok)
        assertEquals(listOf("unrecognized format"), result.warnings)
    }

    @Test
    fun importsKmlPlacemarkWithExtendedData() {
        val kml = """<?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2"><Document>
            <Placemark>
              <name>Bridge Rd</name>
              <ExtendedData><SchemaData>
                <SimpleData name="lanes">2</SimpleData>
                <SimpleData name="priority">2</SimpleData>
                <SimpleData name="route_id">R-2</SimpleData>
                <SimpleData name="oneway">true</SimpleData>
              </SchemaData></ExtendedData>
              <LineString><coordinates>
                -86.0,36.0,0 -86.0,36.01,0
              </coordinates></LineString>
            </Placemark>
            <Placemark><Point><coordinates>-86.0,36.0</coordinates></Point></Placemark>
            </Document></kml>"""
        val result = RoadNetworkImporter.import(kml)
        assertEquals(1, result.imported)
        assertEquals(1, result.skipped)
        val road = result.network.roads.single()
        assertEquals("Bridge Rd", road.name)
        assertEquals(2, road.lanes)
        assertEquals(RoutePriority.P2, road.priority)
        assertEquals("R-2", road.routeId)
        assertTrue(road.oneway)
    }

    @Test
    fun kmlDataValueVariant() {
        val kml = """<kml><Placemark>
            <Data name="lanes"><value>4</value></Data>
            <LineString><coordinates>-86.0,36.0 -86.0,36.01</coordinates></LineString>
            </Placemark></kml>"""
        val result = RoadNetworkImporter.import(kml)
        assertEquals(4, result.network.roads.single().lanes)
    }
}
