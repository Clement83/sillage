package io.github.clement83.sillage.singletrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SingletrackLinkTest {

    @Test
    fun `ride and invitation pages`() {
        assertEquals(
            SingletrackLink.Outing("https://singletrack.quintard.me", "coudon-du-dimanche"),
            SingletrackLink.parse("https://singletrack.quintard.me/s/coudon-du-dimanche"),
        )
        assertEquals(
            SingletrackLink.Invite("https://singletrack.quintard.me", "AbC123"),
            SingletrackLink.parse("https://Singletrack.quintard.me/i/AbC123?utm=x#top"),
        )
    }

    @Test
    fun `locale prefix dropped, sub-path install and port kept`() {
        assertEquals(
            SingletrackLink.Outing("https://example.org", "ride"),
            SingletrackLink.parse("https://example.org/fr/s/ride"),
        )
        assertEquals(
            SingletrackLink.Invite("http://192.0.2.10:3000/st", "tok"),
            SingletrackLink.parse("http://192.0.2.10:3000/st/i/tok/"),
        )
    }

    @Test
    fun `pairing QR`() {
        assertEquals(
            SingletrackLink.Pair("https://singletrack.quintard.me", "K7Q2ZP"),
            SingletrackLink.parse("singletrack://pair?server=https%3A%2F%2Fsingletrack.quintard.me&code=K7Q2ZP"),
        )
        assertNull(SingletrackLink.parse("singletrack://pair?code=K7Q2ZP"))
        assertNull(SingletrackLink.parse("singletrack://pair?server=https%3A%2F%2Fa.b"))
    }

    @Test
    fun `anything else is not a singletrack link`() {
        assertNull(SingletrackLink.parse("https://example.org/"))
        assertNull(SingletrackLink.parse("https://example.org/s/"))
        assertNull(SingletrackLink.parse("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(SingletrackLink.parse("mailto:a@b.c"))
    }

    @Test
    fun `group join link of the outing QR`() {
        assertEquals(
            SingletrackLink.Join("https://singletrack.quintard.me", "opn_42"),
            SingletrackLink.parse("https://singletrack.quintard.me/s/opn_42/join"),
        )
    }

    @Test
    fun `first singletrack link in a shared text`() {
        assertEquals(
            SingletrackLink.Invite("https://singletrack.quintard.me", "tok"),
            SingletrackLink.find("Sortie VTT samedi : https://singletrack.quintard.me/i/tok. À plus !"),
        )
        assertEquals(null, SingletrackLink.find("rien à voir https://example.org/about"))
    }
}
