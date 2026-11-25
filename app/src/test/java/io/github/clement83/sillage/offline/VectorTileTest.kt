package io.github.clement83.sillage.offline

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

class VectorTileTest {
    // Encoded by the reference mapbox-vector-tile library: a `water` layer, then `roads` with a path
    // (100,100 → 2000,100 → 2000,3000), a motorway and a two-part minor road, tile 14/8462/5996.
    private val tile = Base64.getDecoder().decode(
        "Gi4KBXdhdGVyEhESAgAAGAMiCQkAABIUAAAUDxoEa2luZCIHCgV3YXRlciiAIHgCGpcBCgVyb2FkcxIWEgQAAAEBGAIiDAnIAcgBEtgdAACoLRISEgQAAgEDGAIiCAkAwD4KgEAAEhQSAgAEGAIiDAkUFAoUFAkUFAoUFBoEa2luZBoLa2luZF9kZXRhaWwiBgoEcGF0aCIHCgV0cmFjayIJCgdoaWdod2F5IgoKCG1vdG9yd2F5IgwKCm1pbm9yX3JvYWQogCB4Ag==",
    )

    private fun lon(tx: Double) = (8462 + tx / 4096) / (1 shl 14) * 360 - 180
    private fun lat(ty: Double) = atan(sinh(PI * (1 - 2 * (5996 + ty / 4096) / (1 shl 14)))) * 180 / PI

    @Test
    fun `reads the trails of the roads layer, with their kind`() {
        val lines = VectorTile.lines(tile, 14, 8462, 5996, "roads", setOf("path", "minor_road"))
        assertEquals(listOf("path", "minor_road", "minor_road"), lines.map { it.kind })
        assertEquals("track", lines[0].detail)
        val path = lines[0].points
        assertEquals(3, path.size)
        assertEquals(lon(100.0), path[0].lon, 1e-9)
        assertEquals(lat(100.0), path[0].lat, 1e-9)
        assertEquals(lon(2000.0), path[2].lon, 1e-9)
        assertEquals(lat(3000.0), path[2].lat, 1e-9)
        assertEquals(lon(40.0), lines[2].points.last().lon, 1e-9)
    }

    @Test
    fun `gzipped tiles and unwanted kinds`() {
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(tile) } }.toByteArray()
        assertEquals(1, VectorTile.lines(gz, 14, 8462, 5996, "roads", setOf("highway")).size)
        assertEquals(0, VectorTile.lines(gz, 14, 8462, 5996, "transit", setOf("path")).size)
    }
}
