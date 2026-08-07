package io.github.bakahuiii.selene.context

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.io.File

/** Files used by the embedded Syncthing process. They are private to SELENE. */
object SyncthingPaths {
    const val folderId = "selene-inbox-v1"
    const val folderLabel = "SELENE timeline"
    private const val homeDirectoryName = "syncthing"
    private const val syncDirectoryName = "selene-sync"

    fun home(context: Context): File = File(context.noBackupFilesDir, homeDirectoryName)

    fun syncRoot(context: Context): File = File(context.filesDir, syncDirectoryName)

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libsyncthingnative.so")

    fun prepare(context: Context) {
        check(home(context).isDirectory || home(context).mkdirs()) { "无法创建同步核心私有目录" }
        check(syncRoot(context).isDirectory || syncRoot(context).mkdirs()) { "无法创建同步快照私有目录" }
    }

    fun processBuilder(context: Context, command: List<String>): ProcessBuilder = ProcessBuilder(command).apply {
        directory(home(context))
        environment().apply {
            put("HOME", syncRoot(context).absolutePath)
            put("STHOMEDIR", home(context).absolutePath)
            put("STMONITORED", "1")
            put("STNOUPGRADE", "1")
            put("STVERSIONEXTRA", "SELENE")
            put("SQLITE_TMPDIR", context.cacheDir.absolutePath)
            put("GOGC", "100")
            gatewayIpv4(context)?.let { put("FALLBACK_NET_GATEWAY_IPV4", it) }
        }
    }

    private fun gatewayIpv4(context: Context): String? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return@runCatching null
        manager.getLinkProperties(network)?.routes?.firstNotNullOfOrNull { route ->
            route.gateway?.takeIf { route.isDefaultRoute && it is Inet4Address }?.hostAddress
        }
    }.getOrNull()
}
