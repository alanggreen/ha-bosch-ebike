package app.ebikecompanion.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsidTest {
    private val s = Settings(host = "100.64.0.1", homeIp = "192.168.0.10", homeSsids = listOf("HomeNet", " Garage "))

    @Test fun unknownNamesAreNull() {
        listOf(null, "", "  ", "<unknown ssid>", "<UNKNOWN SSID>", "<unknown>", "\"<unknown ssid>\"", "0x", "\"\"").forEach {
            assertNull("[$it]", Ssid.normalize(it))
        }
    }

    @Test fun quotesAndSpacesAreStripped() {
        assertEquals("HomeNet", Ssid.normalize("\"HomeNet\""))
        assertEquals("Home Net", Ssid.normalize("  \" Home Net\" "))
    }

    @Test fun unknownSsidFallsBackToTheRemoteHost() {
        assertEquals("100.64.0.1", s.effectiveHost("<unknown ssid>"))
        assertEquals("100.64.0.1", s.effectiveHost("<unknown>"))
        assertEquals("100.64.0.1", s.effectiveHost(null))
        assertEquals("100.64.0.1", s.effectiveHost(""))
    }

    @Test fun homeNetworkUsesTheHomeIp() {
        assertEquals("192.168.0.10", s.effectiveHost("\"HomeNet\""))
        assertEquals("192.168.0.10", s.effectiveHost("homenet"))
        assertEquals("192.168.0.10", s.effectiveHost("GARAGE"))
        assertEquals("100.64.0.1", s.effectiveHost("Cafe"))
    }

    @Test fun savedNameMatchesEvenWithQuotesOrSpacesInTheSetting() {
        val q = s.copy(homeSsids = listOf("\"HomeNet\" "))
        assertEquals("192.168.0.10", q.effectiveHost("HomeNet"))
    }

    @Test fun anUnknownNameNeverMatchesASavedUnknownEntry() {
        val odd = s.copy(homeSsids = listOf("<unknown ssid>"))
        assertEquals("100.64.0.1", odd.effectiveHost("<unknown ssid>"))
        assertFalse(odd.isHome("<unknown ssid>"))
    }

    @Test fun homeWithoutHomeIpStaysRemote() {
        assertEquals("100.64.0.1", s.copy(homeIp = "").effectiveHost("HomeNet"))
    }

    @Test fun isHomeIsCaseInsensitive() {
        assertTrue(s.isHome("HOMENET"))
        assertFalse(s.isHome(null))
    }

    @Test fun accessReportsTheMissingThing() {
        assertEquals(Ssid.Access.NO_PERMISSION, Ssid.access(permissionGranted = false, locationOn = true))
        assertEquals(Ssid.Access.NO_PERMISSION, Ssid.access(false, false))
        assertEquals(Ssid.Access.LOCATION_OFF, Ssid.access(true, false))
        assertEquals(Ssid.Access.OK, Ssid.access(true, true))
    }

    @Test fun noteIsSilentWithoutHomeNetworksAndLoudWhenBlind() {
        assertEquals("", Ssid.note(Ssid.Access.NO_PERMISSION, false, null, false))
        assertTrue(Ssid.note(Ssid.Access.NO_PERMISSION, true, null, false).contains("location", ignoreCase = true))
        assertTrue(Ssid.note(Ssid.Access.LOCATION_OFF, true, null, false).contains("switched off"))
        assertTrue(Ssid.note(Ssid.Access.OK, true, "HomeNet", true).contains("home address"))
        assertTrue(Ssid.note(Ssid.Access.OK, true, "Cafe", false).contains("remote address"))
        assertTrue(Ssid.note(Ssid.Access.OK, true, null, false).contains("remote address"))
    }

    @Test fun nearbyDropsBlankHiddenAndUnknown() {
        val r = Ssid.nearby(listOf(null to -40, "" to -40, "  " to -40, "<unknown ssid>" to -40, "Cafe" to -70), null, emptyList())
        assertEquals(listOf("Cafe"), r.map { it.name })
    }

    @Test fun nearbyKeepsStrongestOfDuplicatesCaseInsensitive() {
        val r = Ssid.nearby(listOf("HomeNet" to -80, "homenet" to -50, "Other" to -60), null, emptyList())
        assertEquals(listOf("homenet", "Other"), r.map { it.name })
        assertEquals(-50, r[0].level)
    }

    @Test fun nearbySortsStrongestFirstThenByName() {
        val r = Ssid.nearby(listOf("b" to -60, "a" to -60, "z" to -30), null, emptyList())
        assertEquals(listOf("z", "a", "b"), r.map { it.name })
    }

    @Test fun nearbyMarksConnectedAndAdded() {
        val r = Ssid.nearby(listOf("HomeNet" to -40, "Cafe" to -50, "Guest" to -60), "\"cafe\"", listOf(" homenet "))
        assertTrue(r.first { it.name == "HomeNet" }.added)
        assertFalse(r.first { it.name == "HomeNet" }.connected)
        assertTrue(r.first { it.name == "Cafe" }.connected)
        assertFalse(r.first { it.name == "Cafe" }.added)
        assertFalse(r.first { it.name == "Guest" }.connected)
        assertTrue(Ssid.nearby(listOf("Cafe" to -50), "<unknown ssid>", emptyList()).none { it.connected })
    }

    private val utc = java.time.ZoneOffset.UTC
    // 2026-10-11 12:32:07 UTC
    private val t0 = java.time.LocalDateTime.of(2026, 10, 11, 12, 32, 7).toInstant(utc).toEpochMilli()

    @Test fun pressingScanShowsScanningAtOnce() {
        val st = Ssid.scanPressed(t0, startAccepted = true, cachedCount = 4)
        assertEquals(Ssid.ScanPhase.SCANNING, st.phase)
        assertTrue(st.busy)
        assertEquals("Scanning...", Ssid.scanButtonLabel(st))
        assertEquals("Scanning...", Ssid.scanStatus(st, utc))
    }

    @Test fun freshResultsFinishTheScanWithClockAndCount() {
        val st = Ssid.scanResults(Ssid.scanPressed(t0, true, 0), t0 + 4000, 6)
        assertEquals(Ssid.ScanPhase.DONE, st.phase)
        assertFalse(st.busy)
        assertEquals("Updated 12:32:11, 6 networks", Ssid.scanStatus(st, utc))
        assertEquals("Scan again", Ssid.scanButtonLabel(st))
    }

    @Test fun clockUsesTheGivenZone() {
        val st = Ssid.scanResults(Ssid.scanPressed(t0, true, 0), t0, 2)
        assertEquals("Updated 14:32:07, 2 networks", Ssid.scanStatus(st, java.time.ZoneId.of("Europe/Amsterdam")))
    }

    @Test fun oneAndZeroNetworksReadPlainly() {
        assertEquals("Updated 12:32:07, 1 network", Ssid.scanStatus(Ssid.ScanState(Ssid.ScanPhase.DONE, t0, t0, 1), utc))
        assertTrue(Ssid.scanStatus(Ssid.ScanState(Ssid.ScanPhase.DONE, t0, t0, 0), utc).contains("no networks found"))
    }

    @Test fun refusedStartStopsScanningAndSaysSo() {
        val st = Ssid.scanPressed(t0, startAccepted = false, cachedCount = 3)
        assertEquals(Ssid.ScanPhase.THROTTLED, st.phase)
        assertFalse(st.busy)
        assertEquals("Scan again", Ssid.scanButtonLabel(st))
        assertEquals("Android did not start a new scan (it limits scans). Showing the last results from earlier.", Ssid.scanStatus(st, utc))
        assertTrue(Ssid.scanStatus(Ssid.scanPressed(t0, false, 0), utc).contains("no earlier results"))
    }

    @Test fun timeoutOnlyAppliesToARunningScanAfterTenSeconds() {
        val st = Ssid.scanPressed(t0, true, 0)
        assertFalse(Ssid.scanTimedOut(st, t0 + 9_999))
        assertTrue(Ssid.scanTimedOut(st, t0 + 10_000))
        assertFalse(Ssid.scanTimedOut(Ssid.scanResults(st, t0 + 3000, 1), t0 + 20_000))
        assertFalse(Ssid.scanTimedOut(Ssid.ScanState(), t0 + 20_000))
        val done = Ssid.scanResults(st, t0 + 10_000, 5)
        assertEquals(Ssid.ScanPhase.DONE, done.phase)
    }

    @Test fun resultsFromOtherScansDoNotChangeAnIdleOrFinishedState() {
        val idle = Ssid.ScanState()
        assertEquals(idle, Ssid.scanResults(idle, t0, 9))
        val done = Ssid.scanResults(Ssid.scanPressed(t0, true, 0), t0 + 1000, 2)
        assertEquals(done, Ssid.scanResults(done, t0 + 5000, 9))
        val throttled = Ssid.scanPressed(t0, false, 1)
        assertEquals(throttled, Ssid.scanResults(throttled, t0 + 5000, 9))
        assertEquals("", Ssid.scanStatus(idle, utc))
        assertEquals("Scan", Ssid.scanButtonLabel(idle))
    }

    @Test fun scanNoteSaysWhyItIsEmptyOrOld() {
        assertTrue(Ssid.scanNote(Ssid.Access.NO_PERMISSION, 0, true).contains("Allow location"))
        assertTrue(Ssid.scanNote(Ssid.Access.LOCATION_OFF, 3, true).contains("switched off"))
        assertTrue(Ssid.scanNote(Ssid.Access.OK, 0, false).contains("limits"))
        assertTrue(Ssid.scanNote(Ssid.Access.OK, 2, false).contains("old"))
        assertTrue(Ssid.scanNote(Ssid.Access.OK, 0, true).contains("No networks"))
        assertEquals("", Ssid.scanNote(Ssid.Access.OK, 2, true))
    }

    @Test fun signalWordsHaveClearThresholds() {
        assertEquals(Ssid.Signal.STRONG, Ssid.signal(-30))
        assertEquals(Ssid.Signal.STRONG, Ssid.signal(-60))
        assertEquals(Ssid.Signal.FAIR, Ssid.signal(-61))
        assertEquals(Ssid.Signal.FAIR, Ssid.signal(-75))
        assertEquals(Ssid.Signal.WEAK, Ssid.signal(-76))
        assertEquals("Weak", Ssid.signal(-90).label)
    }
}
