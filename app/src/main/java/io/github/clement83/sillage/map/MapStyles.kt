package io.github.clement83.sillage.map

/**
 * Provisional online base map: OpenTopoMap raster tiles, displayed on demand only.
 * Its terms forbid bulk prefetching, so nothing here downloads ahead of what the user looks at;
 * offline maps come later from the paired singletrack server (#1729).
 */
object MapStyles {
    const val OPEN_TOPO_MAP = """
{
  "version": 8,
  "name": "OpenTopoMap",
  "sources": {
    "opentopomap": {
      "type": "raster",
      "tiles": [
        "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
        "https://b.tile.opentopomap.org/{z}/{x}/{y}.png",
        "https://c.tile.opentopomap.org/{z}/{x}/{y}.png"
      ],
      "tileSize": 256,
      "maxzoom": 17,
      "attribution": "© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors, SRTM | © <a href=\"https://opentopomap.org\">OpenTopoMap</a> (CC-BY-SA)"
    }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#f2efe9" } },
    { "id": "opentopomap", "type": "raster", "source": "opentopomap" }
  ]
}
"""
}
