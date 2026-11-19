package com.tubu.trailkit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Serves the bundled OSM MBTiles over loopback HTTP so MapLibre can consume
 * genuine gzip-compressed Mapbox Vector Tiles without a remote tile provider.
 */
internal object LocalMbtilesVectorServer {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var database: SQLiteDatabase? = null
    @Volatile private var styleUrl: String? = null

    @Synchronized
    fun start(context: Context): String? {
        styleUrl?.let { return it }
        return try {
            val file = File(context.filesDir, "yubeng-z18.mbtiles")
            if (!file.exists() || file.length() < 1_000_000L) {
                context.assets.open("yubeng-z18.mbtiles").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            // Tippecanoe's compact MBTiles output uses the normalized map/images
            // schema rather than the optional flat "tiles" table.
            db.rawQuery("SELECT map.tile_id FROM map JOIN images USING(tile_id) LIMIT 1", null).use { cursor ->
                if (!cursor.moveToFirst()) error("MBTiles contains no vector tiles")
            }
            val listener = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            database = db
            server = listener
            val base = "http://127.0.0.1:${listener.localPort}"
            styleUrl = "$base/style.json"
            thread(name = "TrailKit-MBTiles-HTTP", isDaemon = true) {
                while (!listener.isClosed) {
                    try {
                        val client = listener.accept()
                        thread(name = "TrailKit-MBTiles-request", isDaemon = true) { handle(client, base) }
                    } catch (_: Exception) {
                        if (!listener.isClosed) Thread.sleep(100)
                    }
                }
            }
            styleUrl
        } catch (_: Exception) {
            null
        }
    }

    private fun handle(socket: Socket, base: String) {
        socket.use { client ->
            try {
                client.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                val request = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = request.split(' ').getOrNull(1)?.substringBefore('?') ?: return
                if (path == "/style.json") {
                    val body = style(base).toByteArray(StandardCharsets.UTF_8)
                    write(client, 200, "application/json", body, false)
                    return
                }
                val match = Regex("^/(\\d{1,2})/(\\d+)/(\\d+)\\.pbf$").matchEntire(path) ?: run {
                    write(client, 404, "text/plain", ByteArray(0), false); return
                }
                val z = match.groupValues[1].toInt()
                val x = match.groupValues[2].toInt()
                val y = match.groupValues[3].toInt()
                if (z !in 0..18 || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) {
                    write(client, 404, "text/plain", ByteArray(0), false); return
                }
                val tmsY = (1 shl z) - 1 - y
                val bytes = database?.let { db ->
                    synchronized(db) {
                        db.rawQuery(
                            "SELECT images.tile_data FROM map JOIN images USING(tile_id) WHERE map.zoom_level=? AND map.tile_column=? AND map.tile_row=? LIMIT 1",
                            arrayOf(z.toString(), x.toString(), tmsY.toString())
                        ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
                    }
                }
                if (bytes == null) write(client, 204, "application/x-protobuf", ByteArray(0), true)
                else write(client, 200, "application/x-protobuf", bytes, true)
            } catch (_: Exception) {
                try { write(client, 500, "text/plain", ByteArray(0), false) } catch (_: Exception) {}
            }
        }
    }

    private fun write(socket: Socket, status: Int, type: String, body: ByteArray, gzip: Boolean) {
        val reason = when (status) { 200 -> "OK"; 204 -> "No Content"; 404 -> "Not Found"; else -> "Internal Server Error" }
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $type\r\n")
            if (gzip) append("Content-Encoding: gzip\r\n")
            append("Cache-Control: public, max-age=3600\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        socket.getOutputStream().apply { write(headers); write(body); flush() }
    }

    private fun style(base: String) = """
        {
          "version": 8,
          "name": "TrailKit 雨崩 OSM 原生矢量地图",
          "metadata": {"mapbox:autocomposite": false},
          "sources": {
            "osm": {
              "type": "vector",
              "tiles": ["$base/{z}/{x}/{y}.pbf"],
              "minzoom": 0,
              "maxzoom": 18,
              "attribution": "© OpenStreetMap contributors"
            }
          },
          "layers": [
            {"id":"background","type":"background","paint":{"background-color":"#f2f0e9"}},
            {"id":"buildings","type":"fill","source":"osm","source-layer":"osm","filter":["all",["has","building"],["==",["geometry-type"],"Polygon"]],"paint":{"fill-color":"#d5c8b7","fill-outline-color":"#b7a995","fill-opacity":0.92}},
            {"id":"roads-casing","type":"line","source":"osm","source-layer":"osm","filter":["has","highway"],"paint":{"line-color":"#d1c8b8","line-width":["interpolate",["linear"],["zoom"],5,0.5,12,2.2,18,7.0],"line-opacity":0.9}},
            {"id":"roads","type":"line","source":"osm","source-layer":"osm","filter":["has","highway"],"paint":{"line-color":["match",["get","highway"],"motorway","#d77b68","trunk","#dc9872","primary","#e4b77e","secondary","#f1d7a8","tertiary","#fff0c9","path","#a18a68","track","#a18a68","#fffdf7"],"line-width":["interpolate",["linear"],["zoom"],5,0.25,12,1.3,18,4.2],"line-cap":"round","line-join":"round"}},
            {"id":"poi","type":"circle","source":"osm","source-layer":"osm","filter":["any",["has","amenity"],["has","shop"],["has","tourism"],["has","leisure"],["has","public_transport"],["has","healthcare"]],"paint":{"circle-radius":["interpolate",["linear"],["zoom"],8,1.5,14,3.2,18,5.5],"circle-color":["case",["has","tourism"],"#c16b42",["has","shop"],"#4c83a6",["has","amenity"],"#8a6bb1","#638c70"],"circle-stroke-color":"#ffffff","circle-stroke-width":1}},
            {"id":"place-labels","type":"symbol","source":"osm","source-layer":"osm","minzoom":9,"filter":["all",["has","name"],["has","place"]],"layout":{"text-field":["coalesce",["get","name:zh"],["get","name"]],"text-font":["Noto Sans Regular"],"text-size":["interpolate",["linear"],["zoom"],9,11,14,15,18,18],"text-max-width":10,"text-anchor":"center","text-allow-overlap":false,"text-ignore-placement":false},"paint":{"text-color":"#51483c","text-halo-color":"#fffdf7","text-halo-width":2}},
            {"id":"road-labels","type":"symbol","source":"osm","source-layer":"osm","minzoom":13,"filter":["all",["has","name"],["has","highway"]],"layout":{"symbol-placement":"line","text-field":["coalesce",["get","name:zh"],["get","name"]],"text-font":["Noto Sans Regular"],"text-size":["interpolate",["linear"],["zoom"],13,10,18,13],"text-max-angle":30,"text-keep-upright":true},"paint":{"text-color":"#77634d","text-halo-color":"#fffdf7","text-halo-width":1.6}},
            {"id":"poi-labels","type":"symbol","source":"osm","source-layer":"osm","minzoom":13,"filter":["all",["has","name"],["any",["has","amenity"],["has","shop"],["has","tourism"],["has","leisure"],["has","public_transport"],["has","healthcare"]]],"layout":{"text-field":["coalesce",["get","name:zh"],["get","name"]],"text-font":["Noto Sans Regular"],"text-size":["interpolate",["linear"],["zoom"],13,10,18,14],"text-offset":[0,1.1],"text-anchor":"top","text-max-width":9,"text-optional":true},"paint":{"text-color":"#3f4b50","text-halo-color":"#ffffff","text-halo-width":2}},
            {"id":"building-labels","type":"symbol","source":"osm","source-layer":"osm","minzoom":17,"filter":["all",["has","name"],["has","building"]],"layout":{"text-field":["coalesce",["get","name:zh"],["get","name"]],"text-font":["Noto Sans Regular"],"text-size":11,"text-max-width":8,"text-anchor":"center","text-optional":true},"paint":{"text-color":"#665747","text-halo-color":"#fffdf7","text-halo-width":1.5}}
          ]
        }
    """.trimIndent()
}
