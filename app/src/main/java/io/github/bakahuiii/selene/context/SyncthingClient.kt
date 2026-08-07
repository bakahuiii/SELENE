package io.github.bakahuiii.selene.context

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Small REST/CLI adapter for the private Syncthing instance. */
class SyncthingClient(private val context: Context) {
    private val home: File = SyncthingPaths.home(context)
    private val binary: File = SyncthingPaths.binary(context)
    private val baseUrl = "http://127.0.0.1:8384"
    @Volatile private var cachedApiKey: String? = null

    fun waitUntilReady(timeoutMillis: Long = 120_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                request("/rest/system/status", "GET")
                SyncthingRuntimeStatus.ready(context)
                return true
            } catch (error: Exception) {
                lastError = error
                if (SyncthingRuntimeStatus.shouldAbortReadinessWait(context)) break
                Thread.sleep(250L)
            }
        }
        lastError?.let { SyncthingRuntimeStatus.probeFailure(context, "${it.javaClass.simpleName}: ${it.message.orEmpty()}") }
        return false
    }

    fun localDeviceId(): String = runCommand("device-id").trim().lineSequence().last { it.isNotBlank() }

    fun configure(folderPath: File, windowsDeviceId: String, windowsName: String?): String {
        val config = JSONObject(runCommand("cli", "config", "dump-json"))
        val devices = config.optJSONArray("devices") ?: JSONArray().also { config.put("devices", it) }
        if (findById(devices, windowsDeviceId) == null) {
            runCommand(
                "cli", "config", "devices", "add",
                "--device-id=$windowsDeviceId",
                "--name=${windowsName?.takeIf { it.isNotBlank() } ?: "SELENE Windows"}",
            )
        }

        val refreshed = JSONObject(runCommand("cli", "config", "dump-json"))
        val folders = refreshed.optJSONArray("folders") ?: JSONArray().also { refreshed.put("folders", it) }
        val folder = findById(folders, SyncthingPaths.folderId)
        if (folder == null) {
            runCommand(
                "cli", "config", "folders", "add",
                "--id=${SyncthingPaths.folderId}",
                "--label=${SyncthingPaths.folderLabel}",
                "--path=${folderPath.absolutePath}",
                "--type=sendonly",
            )
        } else {
            val actualPath = File(folder.optString("path")).canonicalFile
            if (actualPath != folderPath.canonicalFile || folder.optString("type") != "sendonly") {
                throw IllegalStateException("SELENE sync folder is already configured with a different path or mode")
            }
        }

        val folderJson = JSONObject(runCommand("cli", "config", "folders", SyncthingPaths.folderId, "dump-json"))
        val folderDevices = folderJson.optJSONArray("devices") ?: JSONArray()
        if (findById(folderDevices, windowsDeviceId) == null) {
            runCommand(
                "cli", "config", "folders", SyncthingPaths.folderId,
                "devices", "add", "--device-id=$windowsDeviceId",
            )
        }
        return localDeviceId()
    }

    fun resetRemote(windowsDeviceId: String?) {
        if (windowsDeviceId.isNullOrBlank() || !waitUntilReady(1_000L)) return
        val config = JSONObject(runCommand("cli", "config", "dump-json"))
        val folders = config.optJSONArray("folders") ?: JSONArray()
        for (index in 0 until folders.length()) {
            val folder = folders.optJSONObject(index) ?: continue
            if (folder.optString("id") != SyncthingPaths.folderId) continue
            val devices = folder.optJSONArray("devices") ?: continue
            val kept = JSONArray()
            for (deviceIndex in 0 until devices.length()) {
                val device = devices.optJSONObject(deviceIndex) ?: continue
                if (device.optString("deviceID") != windowsDeviceId) kept.put(device)
            }
            folder.put("devices", kept)
        }
        val devices = config.optJSONArray("devices") ?: JSONArray()
        val keptDevices = JSONArray()
        for (index in 0 until devices.length()) {
            val device = devices.optJSONObject(index) ?: continue
            if (device.optString("deviceID") != windowsDeviceId) keptDevices.put(device)
        }
        config.put("devices", keptDevices)
        putConfig(config)
    }

    fun status(): JSONObject = JSONObject(request("/rest/system/status", "GET"))

    fun folderStatus(): JSONObject = JSONObject(request("/rest/db/status?folder=${SyncthingPaths.folderId}", "GET"))

    fun connections(): JSONObject = JSONObject(request("/rest/system/connections", "GET"))

    fun completion(deviceId: String): JSONObject = JSONObject(
        request("/rest/db/completion?device=$deviceId&folder=${SyncthingPaths.folderId}", "GET"),
    )

    private fun putConfig(config: JSONObject) {
        request("/rest/config", "PUT", config.toString())
    }

    private fun request(path: String, method: String, body: String? = null): String {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 2_000
            readTimeout = 5_000
            doInput = true
            setRequestProperty("X-API-Key", apiKey())
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) throw IllegalStateException("Syncthing REST request failed ($code)")
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun apiKey(): String {
        cachedApiKey?.let { return it }
        return synchronized(this) {
            cachedApiKey?.let { return@synchronized it }
            parseApiKey().also { cachedApiKey = it }
        }
    }

    private fun parseApiKey(): String {
        val config = File(home, "config.xml")
        if (!config.isFile) throw IllegalStateException("Syncthing config has not been generated")
        val parser = Xml.newPullParser()
        config.inputStream().buffered().use { input ->
            parser.setInput(input, StandardCharsets.UTF_8.name())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.DOCDECL) {
                    throw IllegalStateException("Syncthing config must not contain a document type declaration")
                }
                if (event == XmlPullParser.START_TAG && parser.name == "apikey") {
                    return parser.nextText().trim().takeIf { it.isNotEmpty() }
                        ?: throw IllegalStateException("Syncthing GUI API key is empty")
                }
                event = parser.next()
            }
        }
        throw IllegalStateException("Syncthing GUI API key is missing")
    }

    private fun runCommand(vararg arguments: String): String {
        if (!binary.isFile) throw IllegalStateException("SELENE Syncthing native core is missing")
        val command = ArrayList<String>(arguments.size + 1)
        command += binary.absolutePath
        command.addAll(arguments)
        val process = SyncthingPaths.processBuilder(context, command)
            .redirectErrorStream(true)
            .start()
        var output = ""
        val outputReader = Thread {
            output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }.apply {
            name = "SELENE-Syncthing-CLI-Output"
            isDaemon = true
            start()
        }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            throw IllegalStateException("Syncthing command timed out")
        }
        outputReader.join(2_000L)
        if (process.exitValue() != 0) {
            throw IllegalStateException(
                "Syncthing ${arguments.firstOrNull().orEmpty()} failed (${process.exitValue()}): ${output.trim().takeLast(800)}",
            )
        }
        return output
    }

    private fun findById(items: JSONArray, id: String): JSONObject? {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            if (item.optString("deviceID", item.optString("id")) == id) return item
        }
        return null
    }
}
