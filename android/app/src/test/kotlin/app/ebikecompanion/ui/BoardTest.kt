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
}
