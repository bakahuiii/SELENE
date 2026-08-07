package io.github.bakahuiii.selene.context

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.util.Base64

class PairingCodeTest {
    @Test
    fun decodesPinnedPrivateLanOffer() {
        val code = encode(payload("https://192.168.1.5:45678/enroll", Instant.now().plusSeconds(300)))

        val decoded = PairingCode.decode(code)

        assertEquals(deviceId, decoded.windowsDeviceId)
        assertEquals(SyncthingPaths.folderId, decoded.folderId)
        assertEquals("https://192.168.1.5:45678/enroll", decoded.endpoints.single())
    }

    @Test
    fun rejectsExpiredOffer() {
        val code = encode(payload("https://192.168.1.5:45678/enroll", Instant.now().minusSeconds(1)))
        assertThrows(IllegalArgumentException::class.java) { PairingCode.decode(code) }
    }

    @Test
    fun rejectsPublicEnrollmentEndpoint() {
        val code = encode(payload("https://8.8.8.8:45678/enroll", Instant.now().plusSeconds(300)))
        assertThrows(IllegalArgumentException::class.java) { PairingCode.decode(code) }
    }

    private fun payload(endpoint: String, expiry: Instant) = JSONObject()
        .put("schema", "selene-pair/v1")
        .put("windowsDeviceId", deviceId)
        .put("folderId", SyncthingPaths.folderId)
        .put("endpoints", JSONArray().put(endpoint))
        .put("token", "A".repeat(43))
        .put("certificateSha256", "b".repeat(64))
        .put("expiresAt", expiry.toString())
        .put("windowsName", "test")

    private fun encode(json: JSONObject): String {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
        return "selene-pair:v1:$encoded"
    }

    companion object {
        private const val deviceId = "GQIZKJN-OPBGTQL-PAHFEOO-POA7OGF-5HH24OY-HRC2TQV-DSBVHRY-7HI6JQU"
    }
}
