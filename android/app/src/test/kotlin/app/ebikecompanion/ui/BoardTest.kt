package app.ebikecompanion.ui

import app.ebikecompanion.ble.BridgeLink
import app.ebikecompanion.ble.BridgeStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardTest {
    private fun status(flags: Int = 0x1B or 0x04, logger: Int = 1, dropped: Long = 0, failures: Long = 0) =
        BridgeStatus(flags, logger, 0, 0, 1, 1, 0, dropped, failures, 18, 0, 60_000, 1_791_540_000)

    private fun ui(s: BridgeStatus) = BridgeLink.Ui(phase = BridgeLink.Phase.CONNECTED, status = s, statusAtMs = 1000)
    private fun row(u: BridgeLink.Ui, id: String) = Board.rows(u, 2000).first { it.id == id }

    @Test fun aHealthyCardIsOk() {
        assertEquals(MarkState.OK, row(ui(status()), "sd").state)
        assertEquals(MarkState.OK, row(ui(status()), "drop").state)
    }

    @Test fun aCardThatMountedButRefusesWritesIsAProblem() {
        val r = row(ui(status(logger = 2, failures = 169)), "sd")
        assertEquals(MarkState.BAD, r.state)
        assertEquals("Not writing", r.value)
    }

    @Test fun writeFailuresAreCountedAsLostSamples() {
        val r = row(ui(status(logger = 2, failures = 169)), "drop")
        assertEquals(MarkState.BAD, r.state)
        assertEquals("169 lost", r.value)
    }

    @Test fun theProblemPlateNamesTheCardNotTheCounter() {
        val c = Board.count(ui(status(logger = 2, failures = 169)), 2000)
        assertEquals(Tone.BAD, c.tone)
        assertEquals("2 problems", c.title)
        assertEquals("SD card: Not writing", c.sub)
    }

    @Test fun aMissingCardIsStillReported() {
        assertEquals("Missing", row(ui(status(flags = 0x1A)), "sd").value)
    }

    @Test fun refusedCardWritesAreVisibleOnRide() {
        val u = ui(status(logger = 2, failures = 169))
        assertEquals("SD card write failed. 169 samples lost. Check the card; rides may not be saved.", Board.banner(u, 2000))
        assertEquals("Card write failure" to "169 samples lost", Board.footer(u, 2000))
    }

    private val btOff = BridgeLink.Ui(bluetoothOn = false)

    @Test fun bluetoothOffAndNeverLinkedSaysSoWithTheNextStep() {
        val r = row(btOff, "link")
        assertEquals(MarkState.BAD, r.state)
        assertEquals("Bluetooth off", r.value)
        assertEquals("Bluetooth is off. Turn Bluetooth on to connect to the bridge", r.sub)
        assertEquals(Count(Tone.BAD, "Bluetooth is off", "Turn Bluetooth on to connect to the bridge"), Board.count(btOff, 2000))
        assertEquals("Bluetooth is off. Bridge not connected.", Board.banner(btOff, 2000))
    }

    @Test fun bluetoothOnKeepsTheOldWording() {
        val on = BridgeLink.Ui()
        assertEquals("Not connected", row(on, "link").value)
        assertEquals("Tap Connect", row(on, "link").sub)
        assertEquals("Bridge not connected.", Board.banner(on, 2000))
    }

    @Test fun aLinkCutByTheBluetoothSwitchIsExplainedNotJustNotConnected() {
        val cut = ui(status()).copy(bluetoothOn = false, phase = BridgeLink.Phase.IDLE)
        assertEquals(false, Board.linkUp(cut, 2000)) // fresh status, but the switch is off
        val r = row(cut, "link")
        assertEquals("Bluetooth off", r.value)
        assertEquals("Bluetooth is off: the link dropped 1 s ago. Turn Bluetooth on to connect to the bridge", r.sub)
        assertEquals("Bluetooth is off. Bridge lost 1 s. Last values shown.", Board.banner(cut, 2000))
        assertEquals("Card status unknown" to "Bluetooth is off", Board.footer(cut, 2000))
    }

    @Test fun overwrittenSamplesAreVisibleOnRide() {
        val u = ui(status(dropped = 12))
        assertEquals("SD card full. 12 older samples overwritten.", Board.banner(u, 2000))
        assertEquals("Card full" to "12 older samples overwritten", Board.footer(u, 2000))
    }

    @Test fun uploadStateMapsConnectedAndSubscribeResult() {
        fun up(mqtt: Boolean, acks: app.ebikecompanion.sync.AckSub, queued: Int = 0) =
            Board.upload(app.ebikecompanion.sync.SyncEngine.Ui(configured = true, mqttUp = mqtt, queued = queued, acks = acks))
        assertEquals("Online", up(true, app.ebikecompanion.sync.AckSub.OK).value)
        for (a in listOf(app.ebikecompanion.sync.AckSub.DENIED, app.ebikecompanion.sync.AckSub.FAILED)) {
            val r = up(true, a, 3)
            assertEquals(MarkState.WARN, r.state)
            assertEquals("No confirmation", r.value)
            assertEquals("3 waiting: the broker will not let this phone hear back", r.sub)
        }
        assertEquals("Offline", up(false, app.ebikecompanion.sync.AckSub.DENIED).value)
    }
}
