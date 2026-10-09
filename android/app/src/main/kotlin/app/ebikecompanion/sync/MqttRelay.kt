package app.ebikecompanion.sync

import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * One MQTT session to the broker: publishes ride records (QoS 1) and listens for Home Assistant's acknowledgements.
 * Reconnects by itself. It holds no data: the durable queue is [RecordStore], and anything not acknowledged is simply
 * published again.
 */
class MqttRelay(private val s: Settings, private val clientId: String, private val listener: Listener) {
    interface Listener {
        fun onState(up: Boolean, error: String)
        fun onAck(ack: RecordJson.Ack)
    }

    @Volatile var up = false
        private set
    @Volatile private var stopped = false
    private var client: MqttAsyncClient? = null
    private val retry: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mqtt-retry").apply { isDaemon = true } }

    fun start() = retry.execute(::connect)

    fun stop() {
        stopped = true
        up = false
        retry.shutdownNow()
        runCatching { client?.disconnectForcibly(500, 500) }
        runCatching { client?.close() }
    }

    private fun connect() {
        if (stopped) return
        runCatching { client?.close() }
        val c = MqttAsyncClient(s.uri, clientId, MemoryPersistence())
        client = c
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                runCatching { c.subscribe(s.ackTopic, 1) }
                up = true
                listener.onState(true, "")
            }

            override fun connectionLost(cause: Throwable?) {
                up = false
                listener.onState(false, cause?.message ?: "Connection lost")
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val text = message?.payload?.toString(Charsets.UTF_8) ?: return
                RecordJson.parseAck(text)?.let(listener::onAck)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        val opts = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            keepAliveInterval = 30
            connectionTimeout = 10
            maxInflight = 100
            if (s.user.isNotEmpty()) { userName = s.user; password = s.password.toCharArray() }
        }
        c.connect(opts, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {}
            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                up = false
                listener.onState(false, exception?.message ?: "Cannot connect")
                // automatic reconnect only starts after a first success, so retry the first connect ourselves
                if (!stopped) runCatching { retry.schedule(::connect, 15, TimeUnit.SECONDS) }
            }
        })
    }

    /** Fire and forget: a lost publish is recovered by the acknowledgement timeout in the engine. */
    fun publish(json: String) {
        val c = client ?: return
        if (!up) return
        runCatching { c.publish(s.topic, json.toByteArray(Charsets.UTF_8), 1, false) }
    }
}
