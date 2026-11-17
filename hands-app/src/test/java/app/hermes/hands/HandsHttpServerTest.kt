package app.hermes.hands

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class HandsHttpServerTest {
    private fun server(block: (HandsHttpServer) -> Unit) {
        HandsHttpServer({ if (it == "test-token") ClientTokens.Client("one", "Test") else null },
            { ok(JSONObject().put("armed", false)) }, { client, request -> ok(JSONObject().put("client", client.id).put("action", request.getString("action"))) }, 0)
            .use { it.start(); block(it) }
    }
    private fun request(server: HandsHttpServer, method: String = "GET", path: String = "/v1/status", extra: String = "Authorization: Bearer test-token\r\n", body: String = ""): String =
        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(("$method $path HTTP/1.1\r\nHost: 127.0.0.1:${server.localPort}\r\n$extra" +
                "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
    @Test fun statusRequiresValidBearer() = server { host ->
        assertTrue(request(host).startsWith("HTTP/1.1 200"))
        assertTrue(request(host, extra = "").startsWith("HTTP/1.1 401"))
        assertTrue(request(host, extra = "Authorization: Bearer wrong\r\n").startsWith("HTTP/1.1 401"))
    }
    @Test fun rejectsBrowserOriginAndChunkedBodies() = server { host ->
        assertTrue(request(host, extra = "Authorization: Bearer test-token\r\nOrigin: https://attacker.test\r\n").startsWith("HTTP/1.1 403"))
        assertTrue(request(host, extra = "Transfer-Encoding: chunked\r\n").startsWith("HTTP/1.1 403"))
    }
    @Test fun commandsCarryAuthenticatedClientIdentity() = server { host ->
        val reply = request(host, "POST", "/v1/commands", "Authorization: Bearer test-token\r\nContent-Type: application/json\r\n", "{\"action\":\"device.disarm\"}")
        assertTrue(reply.startsWith("HTTP/1.1 200"))
        val json = JSONObject(reply.substringAfter("\r\n\r\n"))
        assertEquals("one", json.getJSONObject("result").getString("client"))
    }
    @Test fun malformedAndDuplicateHeadersFailWithoutDispatch() = server { host ->
        assertTrue(request(host, extra = "Authorization: Bearer test-token\r\nAuthorization: Bearer test-token\r\n").startsWith("HTTP/1.1 400"))
        assertTrue(request(host, "POST", "/v1/commands", "Authorization: Bearer test-token\r\nContent-Type: application/json\r\n", "broken").startsWith("HTTP/1.1 400"))
    }
    @Test fun acceptsAForwardedLoopbackHostPort() = server { host ->
        Socket("127.0.0.1", host.localPort).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(("GET /v1/status HTTP/1.1\r\nHost: 127.0.0.1:18765\r\n" +
                "Authorization: Bearer test-token\r\nConnection: close\r\n\r\n").toByteArray())
            assertTrue(socket.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 200"))
        }
    }

    @Test fun pairingNeverBypassesBearerOnTheLoopbackListener() {
        var paired = false
        HandsHttpServer({ if (it == "test-token") ClientTokens.Client("one", "Test") else null },
            { ok() }, { _, _ -> ok() }, 0, pair = { _, _ -> paired = true; ok() }).use { host ->
            host.start()
            val body = "{\"code\":\"12345678\",\"client_name\":\"Test\"}"
            assertTrue(request(host, "POST", "/v1/pair", "Content-Type: application/json\r\n", body).startsWith("HTTP/1.1 401"))
            assertTrue(request(host, "POST", "/v1/pair", "Authorization: Bearer test-token\r\nContent-Type: application/json\r\n", body).startsWith("HTTP/1.1 404"))
            assertFalse(paired)
        }
    }

    @Test fun rejectsTrailingJsonAndInvalidContentLength() = server { host ->
        assertTrue(request(host, "POST", "/v1/commands", "Authorization: Bearer test-token\r\nContent-Type: application/json\r\n",
            "{\"action\":\"device.disarm\"} ignored").startsWith("HTTP/1.1 400"))
        Socket("127.0.0.1", host.localPort).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(("GET /v1/status HTTP/1.1\r\nHost: localhost:18765\r\n" +
                "Authorization: Bearer test-token\r\nContent-Length: nonsense\r\n\r\n").toByteArray())
            assertTrue(socket.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 400"))
        }
    }
}
