package app.ebikecompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.ebikecompanion.ble.BridgeLink
import app.ebikecompanion.sync.SyncEngine
import app.ebikecompanion.ui.Board
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process, the Bluetooth link and the upload alive while the phone is in a pocket or the screen is off.
 * The link and the sync engine live in [EbikeApp]; this service only anchors them and shows the notification.
 * If Android kills the app, START_STICKY brings the service back and it reconnects.
 */
class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private var ticker = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Ride link", NotificationManager.IMPORTANCE_LOW).apply { description = "Shows that the bridge link and upload are running" },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as EbikeApp
        if (intent?.action == ACTION_STOP) {
            app.link.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, build(app.link.ui.value, app.engine.ui.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (wake == null) {
            // The CPU must stay awake enough to answer MQTT keep-alives and Bluetooth notifications with the screen off.
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ebike:link").apply { acquire(WAKE_LIMIT_MS) }
        }
        // A restart after the app was killed (null intent), or an explicit resume: reconnect to the bridge.
        if ((intent == null || intent.getBooleanExtra(EXTRA_RESUME, false)) && app.link.wantsConnection() && app.link.ui.value.phase == BridgeLink.Phase.IDLE) app.link.connect()
        if (!ticker) {
            ticker = true
            scope.launch {
                while (true) {
                    delay(3_000)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(app.link.ui.value, app.engine.ui.value))
                    // If the rider disconnected from inside the app the service has nothing left to do.
                    if (!app.link.wantsConnection()) { stopSelf(); break }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun build(link: BridgeLink.Ui, up: SyncEngine.Ui): Notification {
        val now = System.currentTimeMillis()
        val connected = Board.linkUp(link, now)
        val title = if (connected) "eBike bridge connected" else "eBike bridge: ${link.message.ifBlank { "not connected" }}"
        val bike = if (link.status?.bikeConnected == true) "Bike linked" else "Bike off"
        val upload = when {
            !up.configured -> "upload not set up"
            up.queued > 0 -> "${up.queued} waiting to upload"
            up.mqttUp -> "all uploaded"
            else -> "Home Assistant unreachable"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(if (connected) "$bike, $upload" else upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "ride_link"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.ebikecompanion.STOP"
        private const val EXTRA_RESUME = "resume"
        private const val WAKE_LIMIT_MS = 8 * 60 * 60 * 1000L

        fun start(ctx: Context, resume: Boolean = false) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, BridgeService::class.java).putExtra(EXTRA_RESUME, resume))

        fun stop(ctx: Context) = ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_STOP))
    }
}
