package io.github.clement83.sillage.offline

import io.github.clement83.sillage.map.MapStyles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The base map once tiles are downloaded (#1729): the server's offline style over the local files,
 * with OpenTopoMap drawn on top. Online, OpenTopoMap covers everything as before; offline, wherever
 * it has no tile the downloaded map shows through. Pure JVM, tested.
 */
object OfflineStyle {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [serverStyle] is the server's `style.json`; its vector source is pointed at [mapUrl] and its
     * relief at [reliefUrl] (dropped when null). Any other source would need the network: dropped
     * with its layers. With [offlineFirst], OpenTopoMap is left out. Falls back to OpenTopoMap alone
     * when the server style is unusable.
     */
    fun compose(serverStyle: String?, mapUrl: String, reliefUrl: String?, offlineFirst: Boolean): String {
        val online = json.parseToJsonElement(MapStyles.OPEN_TOPO_MAP).jsonObject
        val style = serverStyle?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return MapStyles.OPEN_TOPO_MAP
        val sources = style["sources"] as? JsonObject ?: return MapStyles.OPEN_TOPO_MAP
        val layers = style["layers"] as? JsonArray ?: return MapStyles.OPEN_TOPO_MAP

        val kept = LinkedHashMap<String, JsonObject>()
        for ((id, source) in sources) {
            val type = (source as? JsonObject)?.get("type")?.jsonPrimitive?.content
            when {
                type == "vector" -> kept[id] = buildJsonObject {
                    put("type", JsonPrimitive("vector"))
                    put("url", JsonPrimitive(mapUrl))
                }
                type == "raster-dem" && reliefUrl != null -> kept[id] = buildJsonObject {
                    put("type", JsonPrimitive("raster-dem"))
                    put("url", JsonPrimitive(reliefUrl))
                    put("encoding", source.jsonObject["encoding"] ?: JsonPrimitive("terrarium"))
                    put("tileSize", JsonPrimitive(256))
                }
            }
        }
        if (kept.values.none { it["type"]?.jsonPrimitive?.content == "vector" }) return MapStyles.OPEN_TOPO_MAP

        val keptLayers = layers.filter { layer ->
            val source = (layer as? JsonObject)?.get("source")?.jsonPrimitive?.content
            source == null || source in kept
        }.toMutableList()
        val onlineSources = online["sources"]!!.jsonObject
        if (!offlineFirst) {
            onlineSources.forEach { (id, s) -> kept[id] = s.jsonObject }
            keptLayers += online["layers"]!!.jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content != "background" }
        }

        return buildJsonObject {
            style.forEach { (k, v) -> if (k != "sources" && k != "layers" && k != "glyphs" && k != "sprite") put(k, v) }
            put("sources", JsonObject(kept))
            put("layers", JsonArray(keptLayers))
        }.toString()
    }
}
