package app.hermes.companion.data.remote

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class NotificationAckProtocolTest {
    private fun DeviceSocket.frame(json: String) {
        javaClass.getDeclaredMethod("handle", String::class.java).apply { isAccessible = true }.invoke(this, json)
    }
    @Test fun oldReceiverDoesNotEnablePassiveStream() {
        val socket = DeviceSocket(OkHttpClient())
        socket.frame("""{"type":"mobile.controller.hello","device_id":"d"}""")
        assertFalse(socket.passiveNotificationStream)
    }
    @Test fun bothCapabilitiesRequired() {
        val socket = DeviceSocket(OkHttpClient())
        socket.frame("""{"type":"mobile.controller.hello","device_id":"d","server_features":["passive_notification_stream_v1"]}""")
        assertFalse(socket.passiveNotificationStream)
        socket.frame("""{"type":"mobile.controller.hello","device_id":"d","server_features":["passive_notification_stream_v1","notification_delivery_ack_v1"]}""")
        assertTrue(socket.passiveNotificationStream)
        socket.close(); assertFalse(socket.passiveNotificationStream)
    }
    @Test fun ackMustBeBooleanAndConsumedExactlyOnce() {
        val socket = DeviceSocket(OkHttpClient())
        socket.frame("""{"type":"mobile.controller.hello","device_id":"d"}""")
        assertFalse(socket.consumeAcknowledgment("id"))
        socket.frame("""{"type":"mobile.controller.event.ack","delivery_id":"id","accepted":"true"}""")
        assertFalse(socket.consumeAcknowledgment("id"))
        socket.frame("""{"type":"mobile.controller.event.ack","delivery_id":"id","accepted":true}""")
        assertTrue(socket.consumeAcknowledgment("id")); assertFalse(socket.consumeAcknowledgment("id"))
    }
    @Test fun staleAcknowledgmentsCannotPermanentlySaturateCache() {
        val socket = DeviceSocket(OkHttpClient())
        socket.frame("""{"type":"mobile.controller.hello","device_id":"d"}""")
        repeat(300) { socket.frame("""{"type":"mobile.controller.event.ack","delivery_id":"old$it","accepted":true}""") }
        socket.frame("""{"type":"mobile.controller.event.ack","delivery_id":"new","accepted":true}""")
        assertTrue(socket.consumeAcknowledgment("new"))
    }
    @Test fun rejectionNeverCountsAsAccepted() {
        val socket = DeviceSocket(OkHttpClient())
        socket.frame("""{"type":"mobile.controller.hello","device_id":"d"}""")
        socket.frame("""{"type":"mobile.controller.event.ack","delivery_id":"id","accepted":false,"code":"policy_blocked"}""")
        assertFalse(socket.consumeAcknowledgment("id")); assertEquals("policy_blocked", socket.consumeRejection("id"))
        assertNull(socket.consumeRejection("id"))
    }
}
