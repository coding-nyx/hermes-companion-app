package app.hermes.companion.data.local

import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class NotificationOutboxStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private fun store() = NotificationOutboxStore(context, key)
    @Before fun clear() { store().clear() }
    @Test fun encryptedSnapshotSurvivesStoreRecreation() {
        val json = """[{"delivery_id":"stable-id","text":"private-body","version":9}]"""
        store().write(json)
        assertEquals(json, store().read())
        val bytes = File(context.noBackupFilesDir, "notification-outbox-v1").readBytes()
        assertFalse(bytes.toString(Charsets.UTF_8).contains("private-body"))
    }
    @Test fun tamperedCiphertextNeverFallsBackToPlaintext() {
        store().write("[]")
        val file = File(context.noBackupFilesDir, "notification-outbox-v1")
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertThrows(Exception::class.java) { store().read() }
    }
    @Test fun newSnapshotReplacesPriorAndClearRemovesIt() {
        store().write("[1]"); store().write("[2]"); assertEquals("[2]", store().read())
        store().clear(); assertNull(store().read())
    }
    @Test fun oversizedSnapshotRejectedWithoutReplacingExisting() {
        store().write("[]")
        assertThrows(IllegalArgumentException::class.java) { store().write("x".repeat(500001)) }
        assertEquals("[]", store().read())
    }
}
