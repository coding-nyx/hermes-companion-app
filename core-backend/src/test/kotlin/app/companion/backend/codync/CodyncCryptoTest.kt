package app.companion.backend.codync

import app.companion.backend.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CodyncCryptoTest {
    private val fixture=Json.parseToJsonElement(javaClass.getResource("/codync-relay-vectors.json")!!.readText()).jsonObject
    private fun JsonObject.bytes(key:String)=getValue(key).jsonPrimitive.content.decodeBase64Url()
    @Test fun publishedNativeHandshakeAndFrameVectorsMatch() {
        val k=fixture.obj("keys");val h=fixture.obj("handshake")
        val identity=DeviceIdentity(k.bytes("deviceSignSeed"))
        assertArrayEquals(k.bytes("deviceSignPub"),identity.publicKey)
        val input=RelayCrypto.hs1Input(k.bytes("computerId"),identity.publicKey,h.bytes("deviceEphPub"),h.bytes("n"))
        assertArrayEquals(h.bytes("hs1SignInput"),input);assertArrayEquals(h.bytes("hs1Sig"),identity.sign(input))
        val transcript=RelayCrypto.transcriptHash(k.bytes("computerId"),identity.publicKey,h.bytes("deviceEphPub"),h.bytes("n"),h.bytes("hostEphPub"))
        assertArrayEquals(h.bytes("transcriptHash"),transcript)
        assertTrue(RelayCrypto.verify(k.bytes("hostSignPub"),transcript,h.bytes("hs2Sig")))
        val shared=RelayCrypto.sharedSecret(h.bytes("deviceEphPriv"),h.bytes("hostEphPub"));assertArrayEquals(h.bytes("sharedSecret"),shared)
        val keys=RelayCrypto.channelKeys(shared,transcript);assertArrayEquals(h.bytes("kD2H"),keys.d2h);assertArrayEquals(h.bytes("kH2D"),keys.h2d)
        for(row in fixture.rows("frames")) {
            val key=if(row.text("dir")=="d2h") keys.d2h else keys.h2d
            assertArrayEquals(row.bytes("d"),RelayCrypto.sealFrame(key,row.number("c"),row.flag("final"),row.text("message").toByteArray()))
            assertEquals(row.text("message"),RelayCrypto.openFrame(key,row.number("c"),row.bytes("d")).second.toString(Charsets.UTF_8))
        }
    }
    @Test fun replayAndTamperedCiphertextFailClosed() {
        val frames=FrameSealer(ByteArray(32) {8}).seal("hello".toByteArray());val opener=FrameOpener(ByteArray(32) {8})
        assertEquals("hello",opener.open(frames.single())!!.toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { opener.open(frames.single()) }
        val bad=frames.single().data.copyOf().also {it[0]=(it[0].toInt() xor 1).toByte()}
        assertThrows(Exception::class.java) { FrameOpener(ByteArray(32) {8}).open(Frame(0,bad)) }
    }
    @Test fun pairingPinsHostIdentityAndNativePermissionIdentifiersSurvive() {
        val k=fixture.obj("keys");val code=fixture.obj("pairing").text("code")
        val link="codync://pair?v=3&id=${k.text("computerId")}&sk=${k.text("hostSignPub")}&bk=${k.text("hostBoxPub")}&code=$code&urls=http%3A%2F%2F127.0.0.1%3A8900&name=Work"
        assertEquals("Work",CodyncPairing.parse(link).connection.name)
        assertThrows(IllegalArgumentException::class.java) { CodyncPairing.parse(link+"&v=3") }
        assertThrows(IllegalArgumentException::class.java) { CodyncPairing.parse(link.replace(k.text("computerId"),"bogus")) }
        val permission=CodyncBackend.permission(Json.parseToJsonElement("""{"id":"p1","data":{"command":"cargo test","options":[{"optionId":"opaque-allow-8","name":"Allow once","kind":"allow_once"},{"optionId":"opaque-deny-9","name":"Reject","kind":"reject_once"}]}}""").jsonObject)!!
        assertEquals(listOf("opaque-allow-8","opaque-deny-9"),permission.choices)
        assertEquals("Allow once",permission.choiceLabels["opaque-allow-8"])
    }
}
