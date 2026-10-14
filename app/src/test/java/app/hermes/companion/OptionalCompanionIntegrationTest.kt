package app.hermes.companion

import app.hermes.companion.data.remote.HostClientPool
import app.hermes.companion.model.CompanionAvailability
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class OptionalCompanionIntegrationTest {
    @Test fun nativeHostSkipsRoomAndPeerRequestsAfterDiscovery() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<!doctype html><html>Hermes</html>"))
            val origin = server.url("/").toString().trimEnd('/')
            val state = MutableStateFlow(CompanionState(origin = origin))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val manager = RoomSessionManager(HostClientPool(), RoomSeenStore.inMemory(), state, scope)
                manager.refreshRooms()
                manager.refreshPeers()
                withTimeout(5_000) { while (state.value.companionAvailability != CompanionAvailability.UNAVAILABLE) delay(10) }
                manager.refreshRooms()
                delay(100)
                assertEquals(1, server.requestCount)
                assertEquals("/companion/health", server.takeRequest().path)
                assertNull(state.value.roomsError)
                assertFalse(state.value.roomsLoading)
                assertFalse(state.value.peersLoading)
            } finally { scope.cancel() }
        }
    }

    @Test fun lateExtensionDiscoveryCannotAlterAnotherHostsState() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"relay":"ok"}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
            val origin = server.url("/").toString().trimEnd('/')
            val state = MutableStateFlow(CompanionState(origin = origin))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val manager = RoomSessionManager(HostClientPool(), RoomSeenStore.inMemory(), state, scope)
                manager.refreshRooms()
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                state.update { it.copy(origin = "http://other-host") }
                delay(400)
                assertEquals(CompanionAvailability.UNKNOWN, state.value.companionAvailability)
                assertEquals(1, server.requestCount)
            } finally { scope.cancel() }
        }
    }
}
