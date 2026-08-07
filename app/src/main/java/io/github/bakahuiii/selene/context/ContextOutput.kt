package io.github.bakahuiii.selene.context

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Append-only SAF writer. Every successful run owns a new directory and never
 * reads, merges, rewrites, or deletes an older exported snapshot.
 */
object ContextOutput {
    private const val preferencesName = "SELENE"
    private const val outputTreeKey = "output-tree-uri"
    private val snapshotTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
        .withZone(ZoneOffset.UTC)
    private val localTimestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        .withZone(ZoneId.systemDefault())

    fun outputTreeUri(context: Context): Uri? = context
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        .getString(outputTreeKey, null)
        ?.let(Uri::parse)

    fun hasOutputTarget(context: Context): Boolean = PairingManager.isPaired(context) || outputTreeUri(context) != null

    @Synchronized
    fun writeEvents(context: Context, newEvents: List<JSONObject>): Int {
        if (PairingManager.isPaired(context)) return writePrivateSnapshot(context, newEvents)
        val tree = outputTreeUri(context) ?: error("No output folder selected")
        val capturedAt = System.currentTimeMillis()
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val directoryName = "SELENE-v1-${snapshotTimestamp.format(Instant.ofEpochMilli(capturedAt))}"
        val snapshotDirectory = DocumentsContract.createDocument(
            context.contentResolver,
            root,
            DocumentsContract.Document.MIME_TYPE_DIR,
            directoryName,
        ) ?: error("Unable to create immutable snapshot directory")
        val target = DocumentsContract.createDocument(
            context.contentResolver,
            snapshotDirectory,
            "application/json",
            "context-events.json",
        ) ?: error("Unable to create immutable context event file")
        val payload = JSONObject()
            .put("schema", "selene-context-events/v1")
            .put("device", JSONObject().put("platform", "android"))
            .put("generatedAt", iso(capturedAt))
            .put("producer", JSONObject()
                .put("name", "SELENE")
                .put("version", BuildConfig.VERSION_NAME)
                .put("layout", "immutable-snapshot-v1")
            )
            .put("events", JSONArray(newEvents))
        context.contentResolver.openOutputStream(target, "wt")?.bufferedWriter()?.use { it.write(payload.toString()) }
            ?: error("Unable to write immutable context event file")
        return newEvents.size
    }

    /**
     * Syncthing reads this directory directly. The temporary file and atomic rename keep
     * THEIA from observing a half-written JSON document while Syncthing is scanning.
     */
    private fun writePrivateSnapshot(context: Context, newEvents: List<JSONObject>): Int {
        val capturedAt = System.currentTimeMillis()
        val baseName = "SELENE-v1-${snapshotTimestamp.format(Instant.ofEpochMilli(capturedAt))}"
        val root = SyncthingPaths.syncRoot(context)
        check(root.isDirectory || root.mkdirs()) { "Unable to create SELENE sync root" }
        var directory = File(root, baseName)
        var suffix = 1
        while (!directory.mkdir()) {
            directory = File(root, "$baseName-$suffix")
            suffix += 1
            check(suffix < 1_000) { "Unable to create immutable snapshot directory" }
        }
        val payload = JSONObject()
            .put("schema", "selene-context-events/v1")
            .put("device", JSONObject().put("platform", "android"))
            .put("generatedAt", iso(capturedAt))
            .put("producer", JSONObject()
                .put("name", "SELENE")
                .put("version", BuildConfig.VERSION_NAME)
                .put("layout", "immutable-snapshot-v1")
            )
            .put("events", JSONArray(newEvents))
        val temporary = File(directory, "context-events.json.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(payload.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(temporary.renameTo(File(directory, "context-events.json"))) {
            "Unable to publish immutable context event file"
        }
        return newEvents.size
    }

    /** Human-facing event times follow the device's current system timezone. */
    fun iso(millis: Long): String = localTimestamp.format(Instant.ofEpochMilli(millis))
}
