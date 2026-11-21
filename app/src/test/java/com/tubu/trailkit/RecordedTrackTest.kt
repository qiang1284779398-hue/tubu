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
}
