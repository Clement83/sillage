package io.github.clement83.sillage.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatTest {
    @Test
    fun `distance switches from metres to kilometres`() {
        assertEquals("850 m", Format.distance(849.6, Locale.FRANCE))
        assertEquals("4,25 km", Format.distance(4_251.0, Locale.FRANCE))
        assertEquals("4.25 km", Format.distance(4_251.0, Locale.US))
        assertEquals("37,4 km", Format.distance(37_420.0, Locale.FRANCE))
    }

    @Test
    fun `duration shows hours only when needed`() {
        assertEquals("0:00", Format.duration(0))
        assertEquals("4:05", Format.duration(245_000))
        assertEquals("1:04:05", Format.duration(3_845_999))
    }
}
