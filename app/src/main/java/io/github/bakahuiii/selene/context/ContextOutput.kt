package io.github.bakahuiii.selene.context

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
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

    @Synchronized
    fun writeEvents(context: Context, newEvents: List<JSONObject>): Int {
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

    /** Human-facing event times follow the device's current system timezone. */
    fun iso(millis: Long): String = localTimestamp.format(Instant.ofEpochMilli(millis))
}
