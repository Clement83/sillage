package io.github.clement83.sillage.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {

    @Test
    fun `keeps a canonical https address`() {
        assertEquals("https://singletrack.example.org", ServerUrl.normalize("https://singletrack.example.org"))
    }

    @Test
    fun `adds https when the scheme is missing and drops trailing slashes`() {
        assertEquals("https://example.org", ServerUrl.normalize("  example.org// "))
    }

    @Test
    fun `keeps http, port and sub-path, lowercases scheme and host`() {
        assertEquals("http://192.0.2.10:3000/st", ServerUrl.normalize("HTTP://192.0.2.10:3000/st/"))
        assertEquals("https://example.org", ServerUrl.normalize("HTTPS://Example.ORG"))
    }

    @Test
    fun `rejects other schemes, blanks, spaces, queries and credentials`() {
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("   "))
        assertNull(ServerUrl.normalize("ftp://example.org"))
        assertNull(ServerUrl.normalize("https://exa mple.org"))
        assertNull(ServerUrl.normalize("https://example.org/?a=1"))
        assertNull(ServerUrl.normalize("https://user:pw@example.org"))
        assertNull(ServerUrl.normalize("https://"))
    }
}
