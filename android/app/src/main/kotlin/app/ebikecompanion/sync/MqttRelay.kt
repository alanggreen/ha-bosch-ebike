package app.ebikecompanion.sync

import android.util.Log
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
        fun onState(up: Boolean, error: String, acks: AckSub)
        fun onAck(ack: RecordJson.Ack)
    }

    @Volatile var up = false
        private set
    @Volatile private var stopped = false
    private var client: MqttAsyncClient? = null
    private val retry: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mqtt-retry").apply { isDaemon = true } }

    fun start() = retry.execute(::connect)

    private fun log(msg: String) { Log.i(MqttLog.TAG, msg) }

    fun stop() {
        log("relay stop")
        stopped = true
        up = false
        retry.shutdownNow()
        runCatching { client?.disconnectForcibly(500, 500) }
        runCatching { client?.close() }
    }

    private fun connect() {
        if (stopped) return
        runCatching { client?.close() }
        log(MqttLog.connectAttempt(s.uri))
        val c = MqttAsyncClient(s.uri, clientId, MemoryPersistence())
        client = c
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                up = true
                log("connect success (reconnect=$reconnect); subscribing to the ack topic")
                listener.onState(true, "", AckSub.PENDING)
                try {
                    c.subscribe(s.ackTopic, 1, null, object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            val r = SubscribeResult.classify(asyncActionToken?.grantedQos, null)
                            log("ack subscribe: $r granted=${asyncActionToken?.grantedQos?.joinToString(",")}")
                            listener.onState(true, SubscribeResult.reason(r), r)
                        }

                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            log(MqttLog.failure("ack subscribe failed", exception))
                            listener.onState(true, SubscribeResult.reason(AckSub.FAILED, exception), AckSub.FAILED)
                        }
                    })
                } catch (e: Exception) {
                    log(MqttLog.failure("ack subscribe failed", e))
                    listener.onState(true, SubscribeResult.reason(AckSub.FAILED, e), AckSub.FAILED)
                }
            }

            override fun connectionLost(cause: Throwable?) {
                up = false
                log(MqttLog.failure("connection lost; the client reconnects by itself", cause))
                listener.onState(false, cause?.message ?: "Connection lost", AckSub.PENDING)
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
                log(MqttLog.failure("connect failed, retry in 15 s", exception))
                listener.onState(false, exception?.message ?: "Cannot connect", AckSub.PENDING)
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
