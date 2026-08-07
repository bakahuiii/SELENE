package io.github.bakahuiii.selene.context

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object PairingManager {
    private const val preferencesName = "SELENE"
    private const val windowsDeviceKey = "syncthing-windows-device-id"
    private const val windowsNameKey = "syncthing-windows-name"
    private const val pairedAtKey = "syncthing-paired-at"

    fun isPaired(context: Context): Boolean = preferences(context).getString(windowsDeviceKey, null) != null

    fun pairedWindowsDeviceId(context: Context): String? = preferences(context).getString(windowsDeviceKey, null)

    fun status(context: Context): String = preferences(context).getString(windowsDeviceKey, null)?.let {
        val name = preferences(context).getString(windowsNameKey, null).orEmpty()
        if (name.isBlank()) "已配对 Windows ($it)" else "已配对 Windows：$name"
    } ?: "尚未配对 Windows"

    fun queryLiveStatus(context: Context, callback: (String) -> Unit) {
        if (!isPaired(context)) {
            callback("尚未配对 Windows")
            return
        }
        Thread {
            val text = runCatching {
                val client = SyncthingClient(context.applicationContext)
                if (!client.waitUntilReady(1_500L)) {
                    val runtime = SyncthingRuntimeStatus.snapshot(context)
                    return@runCatching if (runtime.failureCount > 0) {
                        "已配对；${SyncthingRuntimeStatus.startupError(context)}"
                    } else {
                        "已配对；同步核心正在启动"
                    }
                }
                val windowsId = pairedWindowsDeviceId(context).orEmpty()
                val connection = client.connections().optJSONObject("connections")?.optJSONObject(windowsId)
                val connected = connection?.optBoolean("connected", false) == true
                val folder = client.folderStatus()
                val state = folder.optString("state", "idle")
                val completion = client.completion(windowsId).optDouble("completion", 0.0)
                val peer = if (connected) "Windows 已连接" else "Windows 当前离线"
                val pending = if (completion >= 99.95) "，远端同步 100%" else "，远端同步 ${"%.1f".format(completion)}%"
                "${status(context)}；$peer；$state$pending"
            }.getOrElse { "${status(context)}；暂时无法读取同步状态" }
            android.os.Handler(context.mainLooper).post { callback(text) }
        }.also {
            it.name = "SELENE-SyncStatus"
            it.isDaemon = true
            it.start()
        }
    }

    fun pair(context: Context, rawCode: String, callback: (Result<Unit>) -> Unit) {
        Thread {
            val result = runCatching { pairBlocking(context.applicationContext, rawCode) }
            android.os.Handler(context.mainLooper).post { callback(result) }
        }.also {
            it.name = "SELENE-Pairing"
            it.isDaemon = true
            it.start()
        }
    }

    fun reset(context: Context) {
        val windowsId = pairedWindowsDeviceId(context)
        runCatching { SyncthingClient(context).resetRemote(windowsId) }
        preferences(context).edit()
            .remove(windowsDeviceKey)
            .remove(windowsNameKey)
            .remove(pairedAtKey)
            .apply()
        SyncthingService.stop(context)
    }

    private fun pairBlocking(context: Context, rawCode: String) {
        val payload = PairingCode.decode(rawCode)
        SyncthingPaths.prepare(context)
        SyncthingService.start(context)
        val client = SyncthingClient(context)
        check(client.waitUntilReady()) { SyncthingRuntimeStatus.startupError(context) }
        val localDeviceId = client.configure(SyncthingPaths.syncRoot(context), payload.windowsDeviceId, payload.windowsName)
        val enrollment = JSONObject()
            .put("schema", "selene-enroll/v1")
            .put("token", payload.token)
            .put("deviceId", localDeviceId)
            .put("folderId", payload.folderId)
        var lastError: Throwable? = null
        for (endpoint in payload.endpoints) {
            try {
                postEnrollment(endpoint, payload.certificateSha256, enrollment)
                preferences(context).edit()
                    .putString(windowsDeviceKey, payload.windowsDeviceId)
                    .putString(windowsNameKey, payload.windowsName.orEmpty())
                    .putLong(pairedAtKey, System.currentTimeMillis())
                    .apply()
                return
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException("Windows 配对请求未送达${lastError?.message?.let { "：$it" }.orEmpty()}")
    }

    private fun postEnrollment(endpoint: String, fingerprint: String, body: JSONObject) {
        val connection = (URL(endpoint).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 6_000
            requestMethod = "POST"
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            sslSocketFactory = pinnedSocketFactory(fingerprint)
            hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Windows 返回 HTTP $code")
        } finally {
            connection.disconnect()
        }
    }

    private fun pinnedSocketFactory(expectedFingerprint: String): javax.net.ssl.SSLSocketFactory {
        val trustManager = object : X509TrustManager {
            private val platform = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm(),
            ).apply { init(null as java.security.KeyStore?) }.trustManagers
                .filterIsInstance<X509TrustManager>()
                .single()

            override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Windows 证书链为空")
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                if (!actual.equals(expectedFingerprint, ignoreCase = true)) {
                    throw CertificateException("Windows 配对证书指纹不匹配")
                }
            }
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }.socketFactory
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
}
