package com.ameer.uyirmesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonBytesTest {

    @Test
    fun jsonRoundTripKeepsTypesAndOrder() {
        val value = linkedMapOf(
            "s" to "hello \"world\"\n\ttab \\ slash / தமிழ் 😀",
            "i" to 42, "l" to 9_000_000_000L, "neg" to -7, "d" to 3.25, "small" to 1.0E-7, "t" to true, "f" to false,
            "n" to null, "list" to listOf(1, "two", listOf(3.5), mapOf("k" to "v")), "empty" to emptyMap<String, Any?>(),
        )
        val text = Json.stringify(value)
        val back = Json.parseObject(text)
        assertEquals(value.keys.toList(), back.keys.toList())
        assertEquals(value["s"], back["s"])
        assertEquals(42L, back["i"])
        assertEquals(9_000_000_000L, back["l"])
        assertEquals(-7L, back["neg"])
        assertEquals(3.25, back["d"])
        assertEquals(1.0E-7, back["small"])
        assertEquals(true, back["t"])
        assertEquals(false, back["f"])
        assertNull(back["n"])
        assertEquals(listOf(1L, "two", listOf(3.5), mapOf("k" to "v")), back["list"])
        assertEquals(text, Json.stringify(back))
    }

    @Test
    fun jsonParsesEscapesAndWhitespace() {
        val m = Json.parseObject(" {\n \"a\" : \"\\u0041\\u0bae\\/\" , \"b\":[ ] ,\"c\":-0.5e2 } ")
        assertEquals("Aம/", m["a"])
        assertEquals(emptyList<Any?>(), m["b"])
        assertEquals(-50.0, m["c"])
        assertEquals(2L, Json.parse("2"))
        assertEquals("x", Json.parse("\"x\""))
    }

    @Test
    fun jsonRejectsBadInput() {
        for (bad in listOf("", "{", "{\"a\":}", "{\"a\":1,}", "[1,2", "\"abc", "{\"a\":1} x", "tru", "-", "{a:1}", "[01x]")) {
            expectThrows<JsonException> { Json.parse(bad) }
        }
        val deep = "[".repeat(100) + "]".repeat(100)
        expectThrows<JsonException> { Json.parse(deep) }
        expectThrows<JsonException> { Json.parseObject("[1]") }
    }

    @Test
    fun jsonAccessorsUseDefaults() {
        val m = Json.parseObject("{\"a\":\"x\",\"n\":5,\"d\":2.5,\"b\":true,\"l\":[\"p\",1,\"q\"],\"o\":{\"z\":1}}")
        assertEquals("x", m.str("a"))
        assertEquals("def", m.str("missing", "def"))
        assertEquals(5, m.int("n"))
        assertEquals(5L, m.long("n"))
        assertEquals(2.5, m.double("d"), 0.0)
        assertNull(m.doubleOrNull("missing"))
        assertTrue(m.bool("b"))
        assertFalse(m.bool("missing"))
        assertEquals(listOf("p", "q"), m.strList("l"))
        assertEquals(1L, m.obj("o")["z"])
        assertEquals(0, m.int("a"))
    }

    @Test
    fun byteWriterAndReaderRoundTrip() {
        val b = ByteWriter().u8(255).u16(65535).i32(-2).i64(Long.MIN_VALUE + 5).str8("தமிழ்").bytes8(byteArrayOf(1, 2))
            .bytes16(ByteArray(300) { it.toByte() }).toByteArray()
        val r = ByteReader(b)
        assertEquals(255, r.u8())
        assertEquals(65535, r.u16())
        assertEquals(-2, r.i32())
        assertEquals(Long.MIN_VALUE + 5, r.i64())
        assertEquals("தமிழ்", r.str8())
        assertArrayEquals(byteArrayOf(1, 2), r.bytes8())
        assertArrayEquals(ByteArray(300) { it.toByte() }, r.bytes16())
        assertEquals(0, r.remaining)
        expectThrows<FormatException> { r.u8() }
        expectThrows<FormatException> { ByteReader(byteArrayOf(5, 1)).bytes8() }
        expectThrows<IllegalArgumentException> { ByteWriter().u8(256) }
        expectThrows<IllegalArgumentException> { ByteWriter().str8("x".repeat(300)) }
    }

    @Test
    fun hexBase64AndIds() {
        val data = Crypto.random(33)
        assertArrayEquals(data, Hex.decode(Hex.encode(data)))
        assertArrayEquals(data, B64.decode(B64.encode(data)))
        assertArrayEquals(data, B64.decodeUrl(B64.encodeUrl(data)))
        expectThrows<FormatException> { Hex.decode("abc") }
        expectThrows<FormatException> { Hex.decode("zz") }
        expectThrows<FormatException> { B64.decode("%%%") }
        val id = Ids.newDeviceId()
        assertTrue(Ids.isValidDeviceId(id))
        assertFalse(Ids.isValidDeviceId("xyz"))
        assertEquals(id.take(8), Ids.shortHex(Ids.shortOf(id)))
        assertEquals("ffffffff", Ids.shortHex(Ids.shortOf("ffffffff00000000")))
        assertTrue(Ids.newId() != Ids.newId())
    }
}
