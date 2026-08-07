package io.github.bakahuiii.selene.context

import android.content.Context

/** Persists a small, sanitized startup diagnostic across service and UI threads. */
object SyncthingRuntimeStatus {
    const val PHASE_IDLE = "idle"
    const val PHASE_CHECKING = "checking"
    const val PHASE_GENERATING = "generating"
    const val PHASE_STARTING = "starting"
    const val PHASE_PROCESS_RUNNING = "process-running"
    const val PHASE_READY = "ready"
    const val PHASE_FAILED = "failed"

    private const val preferencesName = "SELENE_SYNCTHING_RUNTIME"
    private const val phaseKey = "phase"
    private const val detailKey = "detail"
    private const val failureDetailKey = "failure-detail"
    private const val failureCountKey = "failure-count"
    private const val terminalFailureKey = "terminal-failure"
    private const val lastFailureAtKey = "last-failure-at"
    private const val probeDetailKey = "probe-detail"
    private const val updatedAtKey = "updated-at"
    private const val maxDetailLength = 800

    data class Snapshot(
        val phase: String,
        val detail: String,
        val failureDetail: String,
        val failureCount: Int,
        val terminalFailure: Boolean,
        val lastFailureAt: Long,
        val probeDetail: String,
        val updatedAt: Long,
    )

    fun begin(context: Context) {
        preferences(context).edit()
            .putString(phaseKey, PHASE_CHECKING)
            .putString(detailKey, "")
            .putString(failureDetailKey, "")
            .putInt(failureCountKey, 0)
            .putBoolean(terminalFailureKey, false)
            .putLong(lastFailureAtKey, 0L)
            .putString(probeDetailKey, "")
            .putLong(updatedAtKey, System.currentTimeMillis())
            .commit()
    }

    fun phase(context: Context, phase: String, detail: String = "") {
        preferences(context).edit()
            .putString(phaseKey, phase)
            .putString(detailKey, sanitize(context, detail))
            .putLong(updatedAtKey, System.currentTimeMillis())
            .commit()
    }

    fun failure(context: Context, detail: String, terminal: Boolean = false) {
        val preferences = preferences(context)
        val safeDetail = sanitize(context, detail)
        preferences.edit()
            .putString(phaseKey, PHASE_FAILED)
            .putString(detailKey, safeDetail)
            .putString(failureDetailKey, safeDetail)
            .putInt(failureCountKey, preferences.getInt(failureCountKey, 0) + 1)
            .putBoolean(terminalFailureKey, terminal)
            .putLong(lastFailureAtKey, System.currentTimeMillis())
            .putLong(updatedAtKey, System.currentTimeMillis())
            .commit()
    }

    fun ready(context: Context) {
        preferences(context).edit()
            .putString(phaseKey, PHASE_READY)
            .putString(detailKey, "")
            .putString(failureDetailKey, "")
            .putInt(failureCountKey, 0)
            .putBoolean(terminalFailureKey, false)
            .putLong(lastFailureAtKey, 0L)
            .putString(probeDetailKey, "")
            .putLong(updatedAtKey, System.currentTimeMillis())
            .commit()
    }

    fun probeFailure(context: Context, detail: String) {
        preferences(context).edit()
            .putString(probeDetailKey, sanitize(context, detail))
            .putLong(updatedAtKey, System.currentTimeMillis())
            .commit()
    }

    fun snapshot(context: Context): Snapshot {
        val preferences = preferences(context)
        return Snapshot(
            phase = preferences.getString(phaseKey, PHASE_IDLE).orEmpty(),
            detail = preferences.getString(detailKey, "").orEmpty(),
            failureDetail = preferences.getString(failureDetailKey, "").orEmpty(),
            failureCount = preferences.getInt(failureCountKey, 0),
            terminalFailure = preferences.getBoolean(terminalFailureKey, false),
            lastFailureAt = preferences.getLong(lastFailureAtKey, 0L),
            probeDetail = preferences.getString(probeDetailKey, "").orEmpty(),
            updatedAt = preferences.getLong(updatedAtKey, 0L),
        )
    }

    fun shouldAbortReadinessWait(context: Context): Boolean {
        val status = snapshot(context)
        return status.terminalFailure || (
            status.failureCount >= 3 && System.currentTimeMillis() - status.lastFailureAt < 15_000L
            )
    }

    fun startupError(context: Context): String {
        val status = snapshot(context)
        if (status.failureCount > 0 && status.failureDetail.isNotBlank()) {
            return "同步核心启动失败（已重试 ${status.failureCount} 次）：${status.failureDetail}"
        }
        return when (status.phase) {
            PHASE_GENERATING -> "首次生成同步身份超时；部分设备可能需要更长时间，请再试一次"
            PHASE_STARTING -> "同步核心进程启动超时${suffix(status.detail)}"
            PHASE_PROCESS_RUNNING -> "同步核心已运行，但本地接口未就绪${suffix(status.probeDetail)}"
            PHASE_CHECKING -> "同步核心文件检查未完成${suffix(status.detail)}"
            else -> "同步核心未就绪${suffix(status.probeDetail.ifBlank { status.detail })}"
        }
    }

    internal fun sanitizeForStorage(raw: String, privatePaths: List<String>): String {
        var value = raw
        privatePaths.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach {
            value = value.replace(it, "<private>", ignoreCase = true)
        }
        return value
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .takeLast(maxDetailLength)
    }

    private fun sanitize(context: Context, raw: String): String = sanitizeForStorage(
        raw,
        listOf(
            context.applicationInfo.nativeLibraryDir.orEmpty(),
            context.noBackupFilesDir.absolutePath,
            context.filesDir.absolutePath,
            context.cacheDir.absolutePath,
        ),
    )

    private fun suffix(detail: String): String = detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()

    private fun preferences(context: Context) =
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
}
