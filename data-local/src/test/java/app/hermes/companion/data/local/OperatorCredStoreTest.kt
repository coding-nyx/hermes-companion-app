package app.hermes.companion.data.local

import android.content.Context
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
class OperatorCredStoreTest {
    private lateinit var store: OperatorCredStore

    @Before
    fun setUp() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("companion.operator.test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = OperatorCredStore(prefs)
    }

    @Test
    fun saveLoadClear() {
        assertNull(store.load())
        store.save(
            OperatorCred(
                origin = "http://100.64.0.5:9120",
                username = "hermes-admin",
                password = "super-secret-operator-pass",
                sessionToken = "tok-xyz-987",
                authMode = "password",
            ),
        )
        val loaded = store.load()!!
        assertEquals("http://100.64.0.5:9120", loaded.origin)
        assertEquals("hermes-admin", loaded.username)
        assertEquals("super-secret-operator-pass", loaded.password)
        assertEquals("tok-xyz-987", loaded.sessionToken)
        assertEquals("password", loaded.authMode)

        store.clear()
        assertNull(store.load())
    }
}
