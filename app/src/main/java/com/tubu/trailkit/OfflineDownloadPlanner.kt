package com.tubu.trailkit

import kotlin.math.*

internal data class OfflineDownloadRegionPlan(
    val minZoom:Int,
    val maxZoom:Int,
    val bufferMeters:Double,
    val geoJson:String,
    val estimatedTileCount:Int
)

internal data class OfflineDownloadPlan(
    val regions:List<OfflineDownloadRegionPlan>,
    val routeLengthMeters:Double,
    val estimatedTileCount:Int
)

/**
 * Builds GeoJSON corridor polygons instead of downloading every zoom level
 * inside one enormous rectangle around the route's bounding box.
 */
internal object OfflineDownloadPlanner {
    private const val SAMPLE_SPACING_METERS=150.0
    private const val CHUNK_LENGTH_METERS=4000.0
    private const val CHUNK_OVERLAP_METERS=300.0

    fun createPlan(rawPoints:List<Pair<Double,Double>>):OfflineDownloadPlan {
        val points=rawPoints.filter { (lat,lon) ->
            lat.isFinite() && lon.isFinite() && lat in -85.0..85.0 && lon in -180.0..180.0
        }.fold(mutableListOf<Pair<Double,Double>>()) { acc,point ->
            if(acc.isEmpty() || geodesicDistanceMeters(acc.last(),point)>=1.0) acc.add(point)
            acc
        }
        if(points.size<2) return OfflineDownloadPlan(emptyList(),0.0,0)
        val routeLength=(0 until points.lastIndex).sumOf { geodesicDistanceMeters(points[it],points[it+1]) }
        val sampled=resample(points,SAMPLE_SPACING_METERS)
        val chunks=splitIntoChunks(sampled,CHUNK_LENGTH_METERS,CHUNK_OVERLAP_METERS)
        val regions=mutableListOf<OfflineDownloadRegionPlan>()
        // Broad low-zoom coverage, then narrow high-detail coverage around the trail.
        val tiers=listOf(1600.0 to (8 to 12),450.0 to (13 to 16))
        for((buffer,zooms) in tiers) {
            for(chunk in chunks) {
                if(chunk.size<2) continue
                val geoJson=buildCorridorGeoJson(chunk,buffer)
                val estimate=estimateTileCount(chunk,buffer,zooms.first,zooms.second)
                regions.add(OfflineDownloadRegionPlan(zooms.first,zooms.second,buffer,geoJson,estimate))
            }
        }
        return OfflineDownloadPlan(regions,routeLength,regions.sumOf{it.estimatedTileCount})
    }

    internal fun estimateTileCount(
        points:List<Pair<Double,Double>>,
        bufferMeters:Double,
        minZoom:Int,
        maxZoom:Int
    ):Int {
        if(points.size<2 || bufferMeters<=0.0 || minZoom<0 || maxZoom<minZoom)return 0
        val length=(0 until points.lastIndex).sumOf{geodesicDistanceMeters(points[it],points[it+1])}
        val meanLat=Math.toRadians(points.map{it.first}.average())
        val area=length*2.0*bufferMeters+PI*bufferMeters*bufferMeters
        val perimeter=2.0*length+2.0*PI*bufferMeters
        var total=0.0
        for(z in minZoom..maxZoom) {
            val tileMeters=40075016.686*cos(meanLat).coerceAtLeast(0.05)/(1 shl z)
            total += area/(tileMeters*tileMeters) + perimeter/tileMeters*1.5 + 4.0
        }
        return ceil(total.coerceAtLeast(1.0)).toInt()
    }

    private fun resample(points:List<Pair<Double,Double>>,spacing:Double):List<Pair<Double,Double>> {
        val result=mutableListOf(points.first())
        for(i in 0 until points.lastIndex) {
            val a=points[i];val b=points[i+1]
            val distance=geodesicDistanceMeters(a,b)
            val steps=ceil(distance/spacing).toInt().coerceAtLeast(1)
            val deltaLon=wrappedLongitudeDelta(a.second,b.second)
            for(step in 1..steps) {
                val t=step.toDouble()/steps
                val lon=normalizeLongitude(a.second+deltaLon*t)
                val point=(a.first+(b.first-a.first)*t) to lon
                if(geodesicDistanceMeters(result.last(),point)>0.1) result.add(point)
            }
        }
        return result
    }

    private fun splitIntoChunks(
        points:List<Pair<Double,Double>>,
        maxLength:Double,
        overlap:Double
    ):List<List<Pair<Double,Double>>> {
        if(points.size<2)return emptyList()
        val chunks=mutableListOf<List<Pair<Double,Double>>>()
        var start=0
        while(start<points.lastIndex) {
            var end=start+1
            var length=0.0
            while(end<=points.lastIndex) {
                val segment=geodesicDistanceMeters(points[end-1],points[end])
                if(end>start+1 && length+segment>maxLength)break
                length+=segment
                end++
                if(length>=maxLength)break
            }
            val last=(end-1).coerceAtLeast(start+1)
            chunks.add(points.subList(start,last+1).toList())
            if(last>=points.lastIndex)break
            var nextStart=last
            var overlapLength=0.0
            while(nextStart>start+1 && overlapLength<overlap) {
                overlapLength+=geodesicDistanceMeters(points[nextStart-1],points[nextStart])
                nextStart--
            }
            start=max(nextStart,start+1)
        }
        return chunks
    }

    private fun buildCorridorGeoJson(points:List<Pair<Double,Double>>,buffer:Double):String {
        val originLat=points.map{it.first}.average()
        val originLon=points.first().second
        val metersPerDegreeLon=111320.0*cos(Math.toRadians(originLat)).coerceAtLeast(0.05)
        val xy=points.map { (lat,lon) ->
            val unwrappedLon=originLon+wrappedLongitudeDelta(originLon,lon)
            ((unwrappedLon-originLon)*metersPerDegreeLon) to ((lat-originLat)*111320.0)
        }
        fun unit(dx:Double,dy:Double):Pair<Double,Double> {
            val length=hypot(dx,dy).coerceAtLeast(0.0001)
            return dx/length to dy/length
        }
        val left=mutableListOf<Pair<Double,Double>>()
        val right=mutableListOf<Pair<Double,Double>>()
        for(i in xy.indices) {
            val previous=if(i>0)unit(xy[i].first-xy[i-1].first,xy[i].second-xy[i-1].second) else null
            val next=if(i<xy.lastIndex)unit(xy[i+1].first-xy[i].first,xy[i+1].second-xy[i].second) else null
            val (nx,ny)=when {
                previous==null && next!=null -> -next.second to next.first
                next==null && previous!=null -> -previous.second to previous.first
                previous!=null && next!=null -> {
                    val n1=-previous.second to previous.first
                    val n2=-next.second to next.first
                    val sx=n1.first+n2.first
                    val sy=n1.second+n2.second
                    val magnitude=hypot(sx,sy)
                    if(magnitude<0.001) n2 else {
                        val mx=sx/magnitude;val my=sy/magnitude
                        val divisor=abs(mx*n2.first+my*n2.second).coerceAtLeast(0.5)
                        val offset=(buffer/divisor).coerceAtMost(buffer*2.0)
                        (mx*offset/buffer) to (my*offset/buffer)
                    }
                }
                else -> 0.0 to 1.0
            }
            val offsetDistance=buffer
            left.add((xy[i].first+nx*offsetDistance) to (xy[i].second+ny*offsetDistance))
            right.add((xy[i].first-nx*offsetDistance) to (xy[i].second-ny*offsetDistance))
        }
        val ring=(left+right.asReversed()).map { (x,y) ->
            val lon=normalizeLongitude(originLon+x/metersPerDegreeLon)
            val lat=(originLat+y/111320.0).coerceIn(-85.0,85.0)
            lon to lat
        }.toMutableList()
        ring.add(ring.first())
        val coordinates=ring.joinToString(",") { (lon,lat) ->
            "[\${"%.7f".format(java.util.Locale.US,lon)},\${"%.7f".format(java.util.Locale.US,lat)}]"
        }
        return """{"type":"Polygon","coordinates":[[$coordinates]]}"""
    }

    private fun wrappedLongitudeDelta(from:Double,to:Double):Double =
        ((to-from+540.0)%360.0)-180.0

    private fun normalizeLongitude(lon:Double):Double =
        ((lon+540.0)%360.0)-180.0
}
