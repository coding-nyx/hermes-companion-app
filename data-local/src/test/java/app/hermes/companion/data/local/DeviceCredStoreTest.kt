package app.hermes.companion.data.local

import android.content.Context
import app.hermes.companion.model.DeviceCred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DeviceCredStoreTest {
    private lateinit var store: DeviceCredStore

    @Before
    fun setUp() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("companion.device.test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = DeviceCredStore(prefs)
    }

    @Test
    fun saveLoadClear() {
        assertNull(store.load())
        store.save(
            DeviceCred(
                deviceId = "dev_ab",
                profileId = "coder",
                credential = "secret-cred",
                origin = "http://127.0.0.1:9119",
            ),
        )
        val loaded = store.load()!!
        assertEquals("dev_ab", loaded.deviceId)
        assertEquals("coder", loaded.profileId)
        assertEquals("secret-cred", loaded.credential)
        assertEquals("http://127.0.0.1:9119", loaded.origin)
        store.clear()
        assertNull(store.load())
    }
}
