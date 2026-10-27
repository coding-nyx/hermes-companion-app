package app.hermes.desk.providers

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatGptOidcTest {
    private val pair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun claims() = JSONObject().put("iss", ChatGptOidc.ISSUER).put("sub", "account-a").put("aud", "client-a")
        .put("nonce", "fresh-nonce").put("exp", 2000).put("iat", 900)
    private fun signed(claims: JSONObject): String {
        val head = encode(JSONObject().put("alg", "RS256").put("kid", "key-a").toString().toByteArray())
        val input = "$head.${encode(claims.toString().toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(input.toByteArray()) }.sign()
        return "$input.${encode(signature)}"
    }
    private fun keys(): JSONObject {
        val key = pair.public as RSAPublicKey
        return JSONObject().put("keys", JSONArray().put(JSONObject().put("kty", "RSA").put("kid", "key-a").put("alg", "RS256")
            .put("n", encode(key.modulus.toByteArray())).put("e", encode(key.publicExponent.toByteArray()))))
    }
    private fun validate(token: String) = ChatGptOidc.validate(token, keys(), "client-a", "fresh-nonce", 1000)
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected identity rejection") } catch (_: IllegalArgumentException) {} }

    @Test fun pkceMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptOidc.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertNotEquals(ChatGptOidc.random(), ChatGptOidc.random())
    }
    @Test fun verifiesIdentitySignatureAndNonce() { assertEquals("account-a", validate(signed(claims())).getString("sub")) }
    @Test fun rejectsTamperedPayload() {
        val token = signed(claims()).split('.').toMutableList()
        token[1] = encode(claims().put("sub", "attacker").toString().toByteArray())
        rejected { validate(token.joinToString(".")) }
    }
    @Test fun rejectsWrongNonceAudienceIssuerAndExpiredToken() {
        listOf("nonce" to "old-nonce", "aud" to "other-client", "iss" to "https://untrusted.invalid", "exp" to 999).forEach { (key, value) ->
            rejected { validate(signed(claims().put(key, value))) }
        }
    }
    @Test fun multipleAudiencesRequireMatchingAuthorizedParty() {
        val payload = claims().put("aud", JSONArray(listOf("client-a", "other")))
        rejected { validate(signed(payload)) }
        assertEquals("account-a", validate(signed(payload.put("azp", "client-a"))).getString("sub"))
    }
}
