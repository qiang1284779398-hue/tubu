package com.tubu.trailkit

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedTrackTest {
    @Test fun decodesRecordedTrackPointsAndIgnoresWaypoints() {
        val xml = """<gpx><wpt lat="10" lon="20"/><trk><trkseg>
            <trkpt lat="27.123456" lon="100.654321"><ele>3200</ele></trkpt>
            <trkpt lon="100.654500" lat="27.123600"></trkpt>
        </trkseg></trk><rte><rtept lat="30" lon="110"/></rte></gpx>"""
        assertEquals(
            listOf(27.123456 to 100.654321, 27.123600 to 100.654500),
            decodeRecordedTrackPoints(xml)
        )
    }

    @Test fun recoversFlushedPointsFromIncompleteGpx() {
        val xml = """<gpx><trk><trkseg><trkpt lat="1.25" lon="2.5"></trkpt><trkpt lat="1.5" lon="2.75">"""
        assertEquals(listOf(1.25 to 2.5, 1.5 to 2.75), decodeRecordedTrackPoints(xml))
    }

    @Test fun ignoresInvalidOrIncompleteCoordinates() {
        val xml = """<gpx><trkpt lat="91" lon="2"/><trkpt lat="1"/><trkpt lat="1" lon="181"/></gpx>"""
        assertEquals(emptyList<Pair<Double, Double>>(), decodeRecordedTrackPoints(xml))
    }

    @Test fun appendsWaypointDetailsToRecordedGpxAndEscapesXml() {
        val recorded = """<?xml version="1.0"?><gpx><trk><trkseg><trkpt lat="1" lon="2"/></trkseg></trk></gpx>"""
        val waypoints = listOf(
            GpxWaypoint(lat=1.25,lon=2.5,name="营地 & 水源",description="溪边 <帐篷> 位",comment="可取水",type="营地",symbol="Campground",elevation=3210.5,time="2026-10-18T09:30:00Z")
        )
        val exported = appendGpxWaypoints(recorded, waypoints)
        org.junit.Assert.assertTrue(exported.contains("<wpt lat=\"1.25\" lon=\"2.5\">"))
        org.junit.Assert.assertTrue(exported.contains("<name>营地 &amp; 水源</name>"))
        org.junit.Assert.assertTrue(exported.contains("<desc>溪边 &lt;帐篷&gt; 位</desc>"))
        org.junit.Assert.assertTrue(exported.contains("<cmt>可取水</cmt>"))
        org.junit.Assert.assertTrue(exported.contains("<type>营地</type>"))
        org.junit.Assert.assertTrue(exported.contains("<sym>Campground</sym>"))
        org.junit.Assert.assertTrue(exported.contains("<ele>3210.5</ele>"))
        org.junit.Assert.assertTrue(exported.contains("<time>2026-10-18T09:30:00Z</time>"))
        org.junit.Assert.assertTrue(exported.endsWith("</gpx>"))
    }

    @Test fun appendingEmptyWaypointListDoesNotChangeGpx() {
        val recorded = "<gpx><trk></trk></gpx>"
        assertEquals(recorded, appendGpxWaypoints(recorded, emptyList()))
    }

}
