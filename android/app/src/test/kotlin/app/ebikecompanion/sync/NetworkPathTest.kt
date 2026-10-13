package app.ebikecompanion.sync

import app.ebikecompanion.sync.RelayRestart.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPathTest {
    private val wifi = NetPath(wifi = true)
    private val mobile = NetPath(cellular = true)
    private val mobileVpn = NetPath(cellular = true, vpn = true)
    private val wifiVpn = NetPath(wifi = true, vpn = true)
    private val old = 60_000L

    private fun d(oh: String, nh: String, op: NetPath, np: NetPath, connected: Boolean, since: Long = old) =
        RelayRestart.decide(oh, nh, op, np, connected, since)

    @Test fun healthyConnectedRelayIsLeftAlone() {
        assertEquals(Decision.KEEP, d("100.1.1.1", "100.1.1.1", wifi, mobileVpn, connected = true))
        assertEquals(Decision.KEEP, d("100.1.1.1", "100.1.1.1", wifi, wifi, connected = true))
    }

    @Test fun homeToMobileWithVpnRestartsBecauseTheHostChanges() {
        assertEquals(Decision.RESTART, d("192.168.0.10", "100.243.5.1", wifi, mobileVpn, connected = true))
    }

    @Test fun vpnUpWhileDisconnectedRestarts() {
        assertEquals(Decision.RESTART, d("100.1.1.1", "100.1.1.1", wifi, wifiVpn, connected = false))
    }

    @Test fun vpnDownWhileDisconnectedRestarts() {
        assertEquals(Decision.RESTART, d("100.1.1.1", "100.1.1.1", mobileVpn, mobile, connected = false))
    }

    @Test fun wifiToCellularWhileDisconnectedRestartsButNotWhileConnected() {
        assertEquals(Decision.RESTART, d("h", "h", wifi, mobile, connected = false))
        assertEquals(Decision.KEEP, d("h", "h", wifi, mobile, connected = true))
    }

    @Test fun noNetworkAtAllIsNotWorthARestart() {
        assertEquals(Decision.KEEP, d("h", "h", wifi, NetPath(), connected = false))
    }

    @Test fun aBurstIsRateLimitedAndLookedAtLater() {
        assertEquals(Decision.LATER, d("h", "h", wifi, wifiVpn, connected = false, since = 1_000))
        assertEquals(Decision.LATER, d("a", "b", wifi, mobile, connected = true, since = 4_999))
        assertEquals(Decision.RESTART, d("a", "b", wifi, mobile, connected = true, since = 5_000))
    }

    @Test fun nothingChangedNeverRestarts() {
        assertEquals(Decision.KEEP, d("h", "h", wifi, wifi, connected = false))
        assertEquals(Decision.KEEP, d("h", "h", wifi, wifi, connected = false, since = 0))
    }

    @Test fun describe() {
        assertEquals("none", NetPath().describe())
        assertEquals("wifi+vpn", wifiVpn.describe())
        assertTrue(NetPath().none)
        assertFalse(mobile.none)
    }
}
