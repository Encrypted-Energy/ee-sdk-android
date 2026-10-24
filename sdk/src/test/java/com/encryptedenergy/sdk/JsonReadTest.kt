package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 0010 response-body reader. org.json is stubbed in unit tests, so the
 * mint/claim/ingest parses ride [Json.parse]; these pin its behavior.
 */
class JsonReadTest {

    @Test
    fun readsFlatObjectFields() {
        val node = Json.parse("""{"api_key":"ee_live_abc","count":3,"ok":true,"missing":null}""")
        assertEquals("ee_live_abc", Json.str(node, "api_key"))
        assertEquals(3, Json.int(node, "count"))
        assertEquals(true, Json.bool(node, "ok"))
        assertNull(Json.str(node, "missing"))
        assertNull(Json.str(node, "absent"))
    }

    @Test
    fun readsNestedObjectAndArrays() {
        val node = Json.parse("""{"gateway":{"name":"Pixel 7"},"details":["too short","bad email"]}""")
        assertEquals("Pixel 7", Json.str(Json.child(node, "gateway"), "name"))
        assertEquals(listOf("too short", "bad email"), Json.strings(node, "details"))
    }

    @Test
    fun handlesWhitespaceEscapesAndNegatives() {
        val node = Json.parse("""  { "msg" : "line\none\ttwo \"q\"" , "t" : -40 }  """)
        assertEquals("line\none\ttwo \"q\"", Json.str(node, "msg"))
        assertEquals(-40, Json.int(node, "t"))
    }

    @Test
    fun emptyObjectAndArray() {
        assertTrue((Json.parse("{}") as Map<*, *>).isEmpty())
        assertTrue((Json.parse("[]") as List<*>).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedThrows() {
        Json.parse("""{"a":}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun trailingContentThrows() {
        Json.parse("""{"a":1} junk""")
    }
}
