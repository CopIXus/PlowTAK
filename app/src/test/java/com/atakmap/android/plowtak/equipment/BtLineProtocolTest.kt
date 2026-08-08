package com.atakmap.android.plowtak.equipment

import com.atakmap.android.plowtak.model.EquipmentState
import com.atakmap.android.plowtak.model.Material
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtLineProtocolTest {

    // ------------------------------------------------------------- parse

    @Test
    fun `blade commands parse`() {
        assertEquals(
            BtLineProtocol.Command.Blade(down = true),
            BtLineProtocol.parse("BLADE:DOWN")
        )
        assertEquals(
            BtLineProtocol.Command.Blade(down = false),
            BtLineProtocol.parse("BLADE:UP")
        )
    }

    @Test
    fun `spreader commands parse`() {
        assertEquals(
            BtLineProtocol.Command.Spreader(on = true),
            BtLineProtocol.parse("SPREADER:ON")
        )
        assertEquals(
            BtLineProtocol.Command.Spreader(on = false),
            BtLineProtocol.parse("SPREADER:OFF")
        )
    }

    @Test
    fun `rate and temp parse decimals`() {
        assertEquals(
            BtLineProtocol.Command.Rate(250.0),
            BtLineProtocol.parse("RATE:250")
        )
        assertEquals(
            BtLineProtocol.Command.Temp(28.4),
            BtLineProtocol.parse("TEMP:28.4")
        )
        // Sub-zero road temps are a real thing.
        assertEquals(
            BtLineProtocol.Command.Temp(-5.0),
            BtLineProtocol.parse("TEMP:-5")
        )
    }

    @Test
    fun `material commands map to Material`() {
        assertEquals(
            BtLineProtocol.Command.Mat(Material.BRINE),
            BtLineProtocol.parse("MAT:BRINE")
        )
        assertEquals(
            BtLineProtocol.Command.Mat(Material.SALT),
            BtLineProtocol.parse("mat:salt")
        )
    }

    @Test
    fun `keys and values are case-insensitive and whitespace tolerant`() {
        assertEquals(
            BtLineProtocol.Command.Blade(down = true),
            BtLineProtocol.parse("  blade : down  ")
        )
    }

    @Test
    fun `junk lines parse to null`() {
        assertNull(BtLineProtocol.parse(""))
        assertNull(BtLineProtocol.parse("   "))
        assertNull(BtLineProtocol.parse("BLADE"))
        assertNull(BtLineProtocol.parse("BLADE:"))
        assertNull(BtLineProtocol.parse(":DOWN"))
        assertNull(BtLineProtocol.parse("BLADE:SIDEWAYS"))
        assertNull(BtLineProtocol.parse("RATE:abc"))
        assertNull(BtLineProtocol.parse("RATE:-10")) // negative rate
        assertNull(BtLineProtocol.parse("TEMP:9999")) // implausible
        assertNull(BtLineProtocol.parse("MAT:plutonium"))
        assertNull(BtLineProtocol.parse("VOLTAGE:13.8")) // unknown key
    }

    // ------------------------------------------------------------- apply

    @Test
    fun `apply folds a controller session into equipment state`() {
        var s = EquipmentState()
        s = BtLineProtocol.apply(s, "BLADE:DOWN")
        s = BtLineProtocol.apply(s, "SPREADER:ON")
        s = BtLineProtocol.apply(s, "RATE:300.5")
        s = BtLineProtocol.apply(s, "TEMP:26")
        s = BtLineProtocol.apply(s, "MAT:sand")

        assertTrue(s.bladeDown)
        assertTrue(s.saltOn)
        assertEquals(300.5, s.rateLbsPerMi!!, 1e-9)
        assertEquals(26.0, s.roadTempF!!, 1e-9)
        assertEquals(Material.SAND, s.material)

        s = BtLineProtocol.apply(s, "BLADE:UP")
        assertFalse(s.bladeDown)
        // Other channels untouched.
        assertTrue(s.saltOn)
    }

    @Test
    fun `junk lines leave state unchanged`() {
        val s = EquipmentState(bladeDown = true, rateLbsPerMi = 100.0)
        assertEquals(s, BtLineProtocol.apply(s, "garbage"))
        assertEquals(s, BtLineProtocol.apply(s, ""))
    }

    // ---------------------------------------------------------- assembler

    @Test
    fun `assembler joins split chunks and splits joined lines`() {
        val a = BtLineProtocol.LineAssembler()
        assertEquals(emptyList<String>(), a.feed("BLA"))
        assertEquals(emptyList<String>(), a.feed("DE:DO"))
        assertEquals(listOf("BLADE:DOWN"), a.feed("WN\n"))
        assertEquals(
            listOf("SPREADER:ON", "RATE:250"),
            a.feed("SPREADER:ON\nRATE:250\nTEM")
        )
        assertEquals(listOf("TEMP:28"), a.feed("P:28\n"))
    }

    @Test
    fun `assembler tolerates crlf and blank lines`() {
        val a = BtLineProtocol.LineAssembler()
        assertEquals(
            listOf("BLADE:UP", "TEMP:30"),
            a.feed("BLADE:UP\r\n\r\n\nTEMP:30\r\n")
        )
    }

    @Test
    fun `assembler drops runaway garbage instead of growing forever`() {
        val a = BtLineProtocol.LineAssembler(maxLineLength = 16)
        // Over the cap with no newline: the whole oversized line is garbage,
        // including anything more that arrives before its newline.
        a.feed("X".repeat(100))
        assertEquals(emptyList<String>(), a.feed("STILL-GARBAGE"))
        // The newline ends the poisoned line; the next line parses cleanly.
        assertEquals(listOf("BLADE:DOWN"), a.feed("\nBLADE:DOWN\n"))
    }
}
