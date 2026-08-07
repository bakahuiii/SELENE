package io.github.bakahuiii.selene.context

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/** Runs the verified Syncthing native core in SELENE's private app sandbox. */
class SyncthingService : Service() {
    private var process: Process? = null
    private var processThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var initializationFailed = false
    private val stopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        runCatching { SyncthingPaths.prepare(this) }.onFailure { error ->
            initializationFailed = true
            SyncthingRuntimeStatus.begin(this)
            SyncthingRuntimeStatus.failure(
                this,
                "${error.javaClass.simpleName}: ${error.message ?: "无法准备私有目录"}",
                terminal = true,
            )
        }
        multicastLock = runCatching {
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("SELENE-Syncthing")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (initializationFailed) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (processThread?.isAlive != true) startCore()
        return START_STICKY
    }

    override fun onDestroy() {
        stopping.set(true)
        process?.destroy()
        processThread?.interrupt()
        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
        process = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCore() {
        val binary = SyncthingPaths.binary(this)
        SyncthingRuntimeStatus.begin(this)
        if (!binary.isFile) {
            SyncthingRuntimeStatus.failure(
                this,
                "原生核心文件缺失；设备 ABI：${Build.SUPPORTED_ABIS.joinToString()}",
                terminal = true,
            )
            stopSelf()
            return
        }
        if (!binary.canExecute()) {
            SyncthingRuntimeStatus.failure(this, "原生核心文件没有执行权限", terminal = true)
            stopSelf()
            return
        }
        processThread = Thread {
            val home = SyncthingPaths.home(this)
            while (!stopping.get()) {
                try {
                    if (!File(home, "config.xml").isFile) {
                        SyncthingRuntimeStatus.phase(this, SyncthingRuntimeStatus.PHASE_GENERATING)
                        runCommand(binary, "generate")
                    }
                    trimLog()
                    SyncthingRuntimeStatus.phase(this, SyncthingRuntimeStatus.PHASE_STARTING)
                    val command = listOf(
                        binary.absolutePath,
                        "serve",
                        "--no-browser",
                        "--gui-address=127.0.0.1:8384",
                    )
                    val coreProcess = SyncthingPaths.processBuilder(this, command)
                        .redirectErrorStream(true)
                        .start()
                    process = coreProcess
                    SyncthingRuntimeStatus.phase(this, SyncthingRuntimeStatus.PHASE_PROCESS_RUNNING)
                    val recentOutput = captureOutput(coreProcess)
                    val exitCode = coreProcess.waitFor()
                    if (!stopping.get()) {
                        throw IllegalStateException(
                            "核心进程退出，代码 $exitCode${recentOutput.takeIf { it.isNotBlank() }?.let { "；$it" }.orEmpty()}",
                        )
                    }
                } catch (error: Exception) {
                    if (!stopping.get()) {
                        val detail = "${error.javaClass.simpleName}: ${error.message ?: "未知错误"}"
                        Log.e(TAG, "Syncthing startup failed", error)
                        SyncthingRuntimeStatus.failure(this, detail)
                    }
                } finally {
                    process = null
                }
                if (!stopping.get()) runCatching { Thread.sleep(2_000L) }
            }
        }.also {
            it.name = "SELENE-Syncthing"
            it.isDaemon = true
            it.start()
        }
    }

    private fun runCommand(binary: File, vararg args: String) {
        val process = SyncthingPaths.processBuilder(this, listOf(binary.absolutePath) + args.toList())
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        val outputReader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (output.length > 16_384) output.delete(0, output.length - 8_192)
                    output.appendLine(line)
                }
            }
        }.apply {
            name = "SELENE-Syncthing-CommandOutput"
            isDaemon = true
            start()
        }
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            outputReader.join(2_000L)
            throw IllegalStateException("生成同步身份超过 ${COMMAND_TIMEOUT_SECONDS} 秒")
        }
        outputReader.join(2_000L)
        if (process.exitValue() != 0) {
            throw IllegalStateException(
                "命令 ${args.firstOrNull().orEmpty()} 失败，代码 ${process.exitValue()}${
                    output.toString().trim().takeLast(800).takeIf { it.isNotBlank() }?.let { "；$it" }.orEmpty()
                }",
            )
        }
    }

    private fun captureOutput(coreProcess: Process): String {
        val recent = ArrayDeque<String>()
        val log = File(SyncthingPaths.home(this), "syncthing.log")
        FileOutputStream(log, true).bufferedWriter().use { writer ->
            coreProcess.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    writer.appendLine(line)
                    Log.i(TAG_NATIVE, line)
                    recent.addLast(line)
                    while (recent.size > RECENT_LOG_LINES) recent.removeFirst()
                }
            }
        }
        return recent.joinToString(" | ").takeLast(1_200)
    }

    private fun trimLog() {
        val log = File(SyncthingPaths.home(this), "syncthing.log")
        if (!log.isFile || log.length() <= MAX_LOG_BYTES) return
        val tailSize = minOf(LOG_TAIL_BYTES, log.length()).toInt()
        val tail = ByteArray(tailSize)
        java.io.RandomAccessFile(log, "r").use { input ->
            input.seek(log.length() - tailSize)
            input.readFully(tail)
        }
        val replacement = File(log.parentFile, "syncthing.log.tmp")
        replacement.writeBytes(tail)
        if (!replacement.renameTo(log)) {
            log.delete()
            replacement.renameTo(log)
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_selene_context)
        .setContentTitle("SELENE 同步")
        .setContentText("已在后台保持端到端同步")
        .setOngoing(true)
        .setSilent(true)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "SELENE 同步", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        })
    }

    companion object {
        private const val TAG = "SELENE-Syncthing"
        private const val TAG_NATIVE = "SELENE-SyncthingNative"
        private const val CHANNEL_ID = "selene-syncthing"
        private const val NOTIFICATION_ID = 4102
        private const val COMMAND_TIMEOUT_SECONDS = 120L
        private const val RECENT_LOG_LINES = 12
        private const val MAX_LOG_BYTES = 1_048_576L
        private const val LOG_TAIL_BYTES = 524_288L

        fun start(context: Context) {
            val intent = Intent(context, SyncthingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncthingService::class.java))
        }
    }
}
