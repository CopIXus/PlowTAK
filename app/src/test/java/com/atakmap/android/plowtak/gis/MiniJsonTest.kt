package com.atakmap.android.plowtak.gis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun parsesNestedObjectsAndArrays() {
        val v = MiniJson.parseObject(
            """{"a": 1, "b": [true, false, null], "c": {"d": "x"}}"""
        )!!
        assertEquals(1.0, MiniJson.double(v["a"])!!, 0.0)
        val arr = MiniJson.array(v["b"])!!
        assertEquals(listOf(true, false, null), arr)
        assertEquals("x", MiniJson.string(MiniJson.obj(v["c"])!!["d"]))
    }

    @Test
    fun parsesStringEscapes() {
        val v = MiniJson.parseObject("""{"s": "a\"b\\c\nd\u0041"}""")!!
        assertEquals("a\"b\\c\ndA", v["s"])
    }

    @Test
    fun parsesNumbers() {
        val v = MiniJson.parseObject("""{"a": -1.5e2, "b": 0, "c": 12.25}""")!!
        assertEquals(-150.0, MiniJson.double(v["a"])!!, 0.0)
        assertEquals(0, MiniJson.int(v["b"]))
        assertEquals(12.25, MiniJson.double(v["c"])!!, 0.0)
    }

    @Test
    fun emptyContainers() {
        assertEquals(emptyMap<String, Any?>(), MiniJson.parse("{}"))
        assertEquals(emptyList<Any?>(), MiniJson.parse("[]"))
    }

    @Test
    fun malformedReturnsNull() {
        assertNull(MiniJson.parse("{"))
        assertNull(MiniJson.parse("""{"a": }"""))
        assertNull(MiniJson.parse("""{"a": 1} trailing"""))
        assertNull(MiniJson.parse("not json"))
        assertNull(MiniJson.parseObject("[1,2]")) // array is not an object
    }

    @Test
    fun boolCoercion() {
        assertEquals(true, MiniJson.bool(true))
        assertEquals(true, MiniJson.bool("Yes"))
        assertEquals(true, MiniJson.bool(1.0))
        assertEquals(false, MiniJson.bool("0"))
        assertNull(MiniJson.bool("maybe"))
        assertNull(MiniJson.bool(null))
    }

    @Test
    fun quoteEscapesControlCharacters() {
        assertEquals("\"a\\\"b\\nc\"", MiniJson.quote("a\"b\nc"))
        assertTrue(MiniJson.quote("\u0001").contains("\\u0001"))
        // Round trip through the parser.
        assertEquals("a\"b\nc", MiniJson.parse(MiniJson.quote("a\"b\nc")))
    }
}
