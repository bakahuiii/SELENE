package io.github.bakahuiii.selene.context

import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.time.Instant
import java.util.Base64

data class SelenePairingPayload(
    val windowsDeviceId: String,
    val folderId: String,
    val endpoints: List<String>,
    val token: String,
    val certificateSha256: String,
    val expiresAt: Instant,
    val windowsName: String?,
)

object PairingCode {
    private val deviceIdPattern = Regex("^[A-Z2-7]{7}(-[A-Z2-7]{7}){7}$")
    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")

    fun decode(raw: String): SelenePairingPayload {
        val value = raw.trim()
        require(value.isNotEmpty()) { "配对码为空" }
        val jsonText = when {
            value.startsWith("{") -> value
            value.startsWith("selene-pair:v1:", ignoreCase = true) -> decodeBase64(value.substringAfterLast(':'))
            value.startsWith("selene-pair://", ignoreCase = true) -> {
                val query = URI(value).rawQuery.orEmpty()
                decodeBase64(query.split('&').firstOrNull { it.startsWith("data=") }?.substringAfter('=') ?: "")
            }
            else -> decodeBase64(value)
        }
        val json = JSONObject(jsonText)
        require(json.optString("schema") == "selene-pair/v1") { "不是 SELENE v1 配对码" }
        val deviceId = json.optString("windowsDeviceId").uppercase()
        require(deviceIdPattern.matches(deviceId)) { "Windows 设备 ID 无效" }
        val folderId = json.optString("folderId")
        require(folderId == SyncthingPaths.folderId) { "不支持的 SELENE 文件夹" }
        val token = json.optString("token")
        require(token.length in 24..256) { "配对令牌无效" }
        val fingerprint = json.optString("certificateSha256")
        require(sha256Pattern.matches(fingerprint)) { "配对证书指纹无效" }
        val expiresAt = Instant.parse(json.optString("expiresAt"))
        require(expiresAt.isAfter(Instant.now())) { "配对码已过期" }
        val endpointValues = mutableListOf<String>()
        json.optJSONArray("endpoints")?.let { array ->
            for (index in 0 until array.length()) endpointValues += array.optString(index)
        }
        if (endpointValues.isEmpty() && json.has("endpoint")) endpointValues += json.optString("endpoint")
        require(endpointValues.isNotEmpty()) { "配对码没有 Windows 地址" }
        endpointValues.forEach(::validateEndpoint)
        return SelenePairingPayload(
            windowsDeviceId = deviceId,
            folderId = folderId,
            endpoints = endpointValues.distinct(),
            token = token,
            certificateSha256 = fingerprint.lowercase(),
            expiresAt = expiresAt,
            windowsName = json.optString("windowsName").takeIf { it.isNotBlank() },
        )
    }

    private fun decodeBase64(value: String): String {
        require(value.isNotBlank()) { "配对数据为空" }
        val normalized = value.replace('-', '+').replace('_', '/')
            .padEnd(((value.length + 3) / 4) * 4, '=')
        return try {
            String(Base64.getDecoder().decode(normalized), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("配对码格式无效")
        }
    }

    private fun validateEndpoint(endpoint: String) {
        val uri = runCatching { URI(endpoint) }.getOrElse { throw IllegalArgumentException("Windows 地址无效") }
        require(uri.scheme.equals("https", ignoreCase = true) && uri.path == "/enroll") { "配对地址必须是 HTTPS enrollment 地址" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "配对地址包含不支持的参数" }
        val host = uri.host ?: throw IllegalArgumentException("Windows 地址缺少主机")
        val address = runCatching { InetAddress.getByName(host) }.getOrElse { throw IllegalArgumentException("Windows 地址无效") }
        require(address.hostAddress.equals(host, ignoreCase = true)) { "配对地址必须使用 IP" }
        require(address.isSiteLocalAddress || address.isLinkLocalAddress) { "配对地址必须位于本地网络" }
        require(uri.port in 1..65535) { "配对端口无效" }
    }
}
