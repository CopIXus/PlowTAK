package com.atakmap.android.plowtak.gis

import com.atakmap.android.plowtak.model.RoutePriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GhRoadClassTest {
    @Test
    fun `road class bits match the VNS properties layout`() {
        fun flags(ordinal: Int) = ordinal shl GhRoadClass.ROAD_CLASS_SHIFT
        assertEquals(RoutePriority.P1, GhRoadClass.priority(GhRoadClass.roadClass(flags(GhRoadClass.MOTORWAY))))
        assertEquals(RoutePriority.P1, GhRoadClass.priority(GhRoadClass.roadClass(flags(GhRoadClass.PRIMARY))))
        assertEquals(RoutePriority.P2, GhRoadClass.priority(GhRoadClass.roadClass(flags(GhRoadClass.SECONDARY))))
        assertEquals(RoutePriority.P3, GhRoadClass.priority(GhRoadClass.roadClass(flags(GhRoadClass.RESIDENTIAL))))
        assertNull(GhRoadClass.priority(GhRoadClass.roadClass(flags(GhRoadClass.SERVICE))))
        assertNull(GhRoadClass.priority(GhRoadClass.roadClass(flags(GhRoadClass.TRACK))))
    }
}
