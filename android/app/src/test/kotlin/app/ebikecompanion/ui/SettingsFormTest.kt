package app.ebikecompanion.ui

import app.ebikecompanion.sync.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFormTest {
    private val saved = Settings(host = "100.64.0.1", homeIp = "192.168.0.10", user = "bridge", password = "pw", homeSsids = listOf("HomeNet"))
    private val clean = SettingsDraft.from(saved)

    @Test fun unchangedFormIsNotDirty() {
        assertFalse(SettingsForm.isDirty(saved, clean))
        assertFalse(SettingsForm.canSave(saved, clean))
    }

    @Test fun whitespaceAndBlankDefaultsAreNotChanges() {
        assertFalse(SettingsForm.isDirty(saved, clean.copy(host = " 100.64.0.1 ", topic = "  ebike-bridge-mobile/ride_log ")))
        assertFalse(SettingsForm.isDirty(saved, clean.copy(port = "")))
        assertFalse(SettingsForm.isDirty(saved, clean.copy(port = "01883")))
        assertFalse(SettingsForm.isDirty(saved.copy(topic = ""), clean.copy(topic = "")))
    }

    @Test fun anyRealEditIsDirty() {
        assertTrue(SettingsForm.isDirty(saved, clean.copy(host = "100.64.0.2")))
        assertTrue(SettingsForm.isDirty(saved, clean.copy(port = "8883")))
        assertTrue(SettingsForm.isDirty(saved, clean.copy(tls = true)))
        assertTrue(SettingsForm.isDirty(saved, clean.copy(password = "x")))
        assertTrue(SettingsForm.isDirty(saved, clean.copy(secret = "s")))
        assertTrue(SettingsForm.isDirty(saved, clean.copy(rotateScreen = true)))
        assertTrue(SettingsForm.isDirty(saved, clean.copy(homeSsids = listOf("HomeNet", "Garage"))))
        assertTrue(SettingsForm.isDirty(saved, clean.copy(port = "abc")))
    }

    @Test fun saveNeedsAChangeAndAValidForm() {
        assertTrue(SettingsForm.canSave(saved, clean.copy(tls = true)))
        val noAddress = clean.copy(host = " ", homeIp = "")
        assertTrue(SettingsForm.isDirty(saved, noAddress))
        assertFalse(SettingsForm.canSave(saved, noAddress))
        assertEquals(SettingsForm.Problem.NO_ADDRESS, SettingsForm.problem(noAddress))
        assertTrue(SettingsForm.blockedReason(noAddress)!!.contains("address"))
        assertNull(SettingsForm.blockedReason(clean))
        // one address is enough
        assertTrue(SettingsForm.canSave(saved, clean.copy(host = "", tls = true)))
    }

    @Test fun badPortBlocksSaveAndOpensAdvanced() {
        for (p in listOf("0", "65536", "99999999999", "x")) {
            val d = clean.copy(port = p)
            assertEquals(p, SettingsForm.Problem.BAD_PORT, SettingsForm.problem(d))
            assertFalse(SettingsForm.canSave(saved, d))
            assertTrue(SettingsForm.advancedNeedsAttention(d))
        }
        assertFalse(SettingsForm.advancedNeedsAttention(clean))
        assertNull(SettingsForm.problem(clean.copy(port = "65535")))
    }

    @Test fun blankPortSaysWhatWillBeUsed() {
        assertEquals(1883, clean.copy(port = "").toSettings().port)
        assertTrue(SettingsForm.portHint(clean.copy(port = "")).contains("1883 will be used"))
        assertEquals("Use a number from 1 to 65535.", SettingsForm.portHint(clean.copy(port = "70000")))
        assertTrue(SettingsForm.tlsHint(clean.copy(port = "")).endsWith("Port now: 1883."))
    }

    @Test fun advancedSummaryNamesUserAndPort() {
        assertEquals("bridge on port 1883", SettingsForm.advancedSummary(clean))
        assertEquals("No user name on port 8883", SettingsForm.advancedSummary(clean.copy(user = " ", port = "8883")))
        assertEquals("bridge on port 1883", SettingsForm.advancedSummary(clean.copy(port = "")))
    }

    @Test fun toSettingsAppliesDefaultsAndTrims() {
        val s = clean.copy(host = " h ", homeIp = " 10.0.0.1 ", topic = " ", port = "").toSettings()
        assertEquals("h", s.host)
        assertEquals("10.0.0.1", s.homeIp)
        assertEquals(Settings().topic, s.topic)
        assertEquals(1883, s.port)
    }

    @Test fun hostUsedIsTheOneActuallyChosen() {
        val d = clean.copy(host = "100.64.0.9", homeIp = "192.168.0.10", homeSsids = listOf("HomeNet"))
        assertEquals("192.168.0.10", SettingsForm.hostUsed(d, "\"HomeNet\""))
        assertEquals("100.64.0.9", SettingsForm.hostUsed(d, "Cafe"))
        assertEquals("100.64.0.9", SettingsForm.hostUsed(d, null))
        assertEquals("100.64.0.9", SettingsForm.hostUsed(d, "<unknown ssid>"))
        assertEquals("", SettingsForm.hostUsed(d.copy(host = ""), "Cafe"))
    }

    @Test fun connectionLineIsPlainAndNamesTheHost() {
        assertEquals("Not set up yet.", SettingsForm.connectionLine(false, null, false, 0, "").text)
        val up = SettingsForm.connectionLine(true, null, true, 3, "")
        assertEquals("Connected to the broker.", up.text)
        assertEquals("3 records waiting.", up.more)
        assertEquals("1 record waiting.", SettingsForm.connectionLine(true, null, true, 1, "").more)
        assertEquals("Saved. Connecting to 192.168.0.10...", SettingsForm.connectionLine(true, "192.168.0.10", false, 0, "").text)
        assertTrue(SettingsForm.connectionLine(true, "", false, 0, "").text.contains("No address"))
        val bad = SettingsForm.connectionLine(true, "h", false, 0, "java.net.ConnectException: failed to connect to /192.168.0.10 (port 1883) after 30000ms")
        assertEquals("Not connected to the broker.", bad.text)
        assertTrue(bad.more.startsWith("Reason: java.net.ConnectException"))
        assertTrue(bad.more.contains("Check the address"))
        assertFalse(SettingsForm.connectionLine(true, null, false, 0, "").more.contains("Reason"))
    }

    @Test fun shortErrorCollapsesAndCuts() {
        assertEquals("a b", SettingsForm.shortError("  a \n b "))
        assertEquals("x".repeat(80) + "...", SettingsForm.shortError("x".repeat(200)))
    }

    @Test fun homeNetworkListRules() {
        var d = SettingsForm.addSsid(clean, "  Garage ")!!
        assertEquals(listOf("HomeNet", "Garage"), d.homeSsids)
        assertNull(SettingsForm.addSsid(d, "garage"))
        assertNull(SettingsForm.addSsid(d, "   "))
        d = SettingsForm.addSsid(SettingsForm.addSsid(SettingsForm.addSsid(d, "c")!!, "d")!!, "e")!!
        assertEquals(5, d.homeSsids.size)
        assertNull(SettingsForm.addSsid(d, "f"))
        assertEquals(d, SettingsForm.restoreSsid(d, "f", 0))
    }

    @Test fun deleteCanBeUndoneInPlace() {
        val d = clean.copy(homeSsids = listOf("A", "B", "C"))
        val (after, idx) = SettingsForm.removeSsid(d, "B")!!
        assertEquals(listOf("A", "C"), after.homeSsids)
        assertEquals(1, idx)
        assertEquals(d.homeSsids, SettingsForm.restoreSsid(after, "B", idx).homeSsids)
        assertEquals(listOf("A", "C", "B"), SettingsForm.restoreSsid(after, "B", 9).homeSsids)
        assertNull(SettingsForm.removeSsid(d, "Z"))
        // undo after the rider re-added the same name does not duplicate it
        assertEquals(listOf("A", "C", "b"), SettingsForm.restoreSsid(after.copy(homeSsids = listOf("A", "C", "b")), "B", 1).homeSsids)
    }

    @Test fun reconcileKeepsUnsavedEditsAndFollowsCleanForms() {
        val newBase = SettingsDraft.from(saved.copy(host = "100.64.0.7"))
        val dirty = clean.copy(password = "typing")
        // equal re-emit: nothing moves, even for a dirty draft
        assertEquals(dirty, SettingsForm.reconcile(clean, clean, dirty))
        // a clean draft follows a changed saved value
        assertEquals(newBase, SettingsForm.reconcile(clean, newBase, clean))
        // a dirty draft is kept when the saved settings change underneath
        assertEquals(dirty, SettingsForm.reconcile(clean, newBase, dirty))
    }
}
