package io.github.bakahuiii.selene.context

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult

/** One-time configuration screen for the local timeline and movement collectors. */
class MainActivity : Activity() {
    private val outputTreeKey = "output-tree-uri"
    private val requestOutputTree = 1001
    private val requestCalendar = 1002
    private val requestLocation = 1003
    private val requestInitial = 1004
    private val requestBackground = 1005
    private val requestNotifications = 1006
    private val initialPermissionGuidanceKey = "initial-permission-guidance-v1"
    private val movementNotificationGuidanceKey = "movement-notification-guidance-v1"
    private lateinit var preferences: SharedPreferences
    private lateinit var status: TextView
    private lateinit var pairingStatus: TextView
    private var automaticToggle: Switch? = null
    private var backgroundToggle: Switch? = null
    private var onlinePlaceToggle: Switch? = null
    private var pendingPermission: PermissionFlow? = null
    private var initialSettingsPage: InitialSettingsPage? = null
    private var synchronizingToggles = false

    private enum class PermissionFlow { CALENDAR, BACKGROUND_LOCATION, INITIAL }
    private enum class InitialSettingsPage { BACKGROUND, USAGE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences("SELENE", Context.MODE_PRIVATE)
        setContentView(createView())
        if (PairingManager.isPaired(this)) SyncthingService.start(this)
        updateStatus()
        window.decorView.post { requestInitialPermissionsIfNeeded() }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            if (PairingManager.isPaired(this)) SyncthingService.start(this)
            syncMovementTracking()
            updateStatus()
            when (initialSettingsPage) {
                InitialSettingsPage.BACKGROUND -> {
                    initialSettingsPage = null
                    openInitialUsageSettings()
                }
                InitialSettingsPage.USAGE -> initialSettingsPage = null
                null -> Unit
            }
        }
    }

    private fun createView(): ViewGroup {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = "SELENE"
            textSize = 22f
        })
        content.addView(TextView(this).apply {
            text = "一次设置后，应用会在本机按小时汇总可用的时间线背景。不会上传聊天内容，不监听通知，不读取其它应用数据库，也不会主动启动地图应用。"
            textSize = 14f
            setPadding(0, padding / 2, 0, padding / 2)
        })
        status = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, padding) }
        content.addView(status)

        content.addView(sectionTitle("远程同步"))
        content.addView(TextView(this).apply {
            text = "扫描 Windows SELENE 显示的一次性二维码，或粘贴配对码。配对只在同一局域网进行一次；之后快照由内置 Syncthing 自动跨网络同步。"
            textSize = 13f
        })
        pairingStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, padding / 3, 0, padding / 3)
        }
        content.addView(pairingStatus)
        content.addView(button("扫描 Windows 配对二维码") { scanPairingCode() })
        content.addView(button("输入 Windows 配对码") { enterPairingCode() })
        content.addView(button("解除配对（保留本地快照）") { resetPairing() })

        content.addView(sectionTitle("存储"))
        content.addView(button("选择 SELENE 导出文件夹") { chooseOutputFolder() })

        content.addView(sectionTitle("自动采集"))
        content.addView(TextView(this).apply {
            text = "日历、应用和设备快照通常每小时采集一次，系统可能为了省电延后执行。开启后台位置后，SELENE 会通过常驻通知持续判断移动并记录已确认的轨迹。"
            textSize = 13f
        })
        automaticToggle = Switch(this).apply {
            text = "启用自动本地采集"
            isChecked = AutoCollectionSettings.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                if (synchronizingToggles) return@setOnCheckedChangeListener
                if (checked) enableAutomatic() else disableAutomatic()
            }
        }
        content.addView(automaticToggle)
        backgroundToggle = Switch(this).apply {
            text = "允许后台持续记录移动轨迹和大致速度"
            isChecked = AutoCollectionSettings.backgroundLocationEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                if (synchronizingToggles) return@setOnCheckedChangeListener
                if (checked) enableBackgroundLocation() else disableBackgroundLocation()
            }
        }
        content.addView(backgroundToggle)
        onlinePlaceToggle = Switch(this).apply {
            text = "在线地点补全（可选）"
            isChecked = AutoCollectionSettings.onlinePlaceEnrichmentEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                if (synchronizingToggles) return@setOnCheckedChangeListener
                if (checked && (!AutoCollectionSettings.isEnabled(this@MainActivity) || !AutoCollectionSettings.backgroundLocationEnabled(this@MainActivity))) {
                    toast("请先启用自动采集和后台位置")
                    isChecked = false
                } else {
                    AutoCollectionSettings.setOnlinePlaceEnrichmentEnabled(this@MainActivity, checked)
                }
                updateStatus()
            }
        }
        content.addView(onlinePlaceToggle)
        content.addView(TextView(this).apply {
            text = "地点补全仅对新发现的地点聚类低频请求 OpenStreetMap Nominatim。请求只包含坐标，应用只保存“学校 · 名称”这类短标签，不保存完整地址；标签不会把坐标传给模型。"
            textSize = 13f
            setPadding(0, padding / 3, 0, 0)
        })

        content.addView(sectionTitle("系统授权"))
        content.addView(button("打开“使用情况访问”设置") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        content.addView(button("打开 SELENE 应用权限设置") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        content.addView(TextView(this).apply {
            text = "使用情况访问用于获取应用前台使用时段。Android 10 及以上的后台位置需要在系统页面选择“始终允许”。"
            textSize = 13f
            setPadding(0, padding / 3, 0, 0)
        })
        return ScrollView(this).apply { addView(content) }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun sectionTitle(label: String) = TextView(this).apply {
        text = label
        textSize = 17f
        setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
    }

    private fun updateStatus() {
        val folder = when {
            PairingManager.isPaired(this) -> "已配对（应用私有同步目录）"
            outputTreeUri() == null -> "未选择"
            else -> "已选择"
        }
        val calendar = if (hasPermission(Manifest.permission.READ_CALENDAR)) "已授权" else "未授权"
        val location = if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) "已授权" else "未授权"
        val usage = if (hasUsageAccess()) "已授权" else "未授权（需在系统设置打开）"
        val background = when {
            !AutoCollectionSettings.backgroundLocationEnabled(this) -> "未启用"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> "等待“始终允许”"
            !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) -> "等待精确位置授权"
            else -> "已启用"
        }
        val automatic = if (AutoCollectionSettings.isEnabled(this)) "已启用" else "未启用"
        val online = if (AutoCollectionSettings.onlinePlaceEnrichmentEnabled(this)) "已启用（新地点低频请求）" else "未启用"
        status.text = "自动采集：$automatic\n后台位置：$background\n在线地点补全：$online\n导出文件夹：$folder\n日历：$calendar    使用情况访问：$usage\n精确位置：$location"
        pairingStatus.text = PairingManager.status(this)
        if (PairingManager.isPaired(this)) {
            val expectedDevice = PairingManager.pairedWindowsDeviceId(this)
            PairingManager.queryLiveStatus(this) { text ->
                if (PairingManager.pairedWindowsDeviceId(this) == expectedDevice) pairingStatus.text = text
            }
        }
        synchronizingToggles = true
        automaticToggle?.isChecked = AutoCollectionSettings.isEnabled(this)
        backgroundToggle?.isChecked = AutoCollectionSettings.backgroundLocationEnabled(this)
        onlinePlaceToggle?.isChecked = AutoCollectionSettings.onlinePlaceEnrichmentEnabled(this)
        synchronizingToggles = false
        onlinePlaceToggle?.isEnabled = AutoCollectionSettings.isEnabled(this)
    }

    private fun enableAutomatic() {
        if (!ContextOutput.hasOutputTarget(this)) {
            toast("请先选择 SELENE 导出文件夹")
            automaticToggle?.isChecked = false
            return
        }
        AutoCollectionSettings.setEnabled(this, true)
        AutoCollectionScheduler.start(this)
        syncMovementTracking()
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            pendingPermission = PermissionFlow.CALENDAR
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), requestCalendar)
        }
        updateStatus()
    }

    private fun disableAutomatic() {
        AutoCollectionSettings.setEnabled(this, false)
        AutoCollectionScheduler.stop(this)
        syncMovementTracking()
        updateStatus()
    }

    private fun enableBackgroundLocation() {
        if (!AutoCollectionSettings.isEnabled(this)) {
            toast("请先启用自动采集")
            backgroundToggle?.isChecked = false
            return
        }
        AutoCollectionSettings.setBackgroundLocationEnabled(this, true)
        syncMovementTracking()
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            pendingPermission = PermissionFlow.BACKGROUND_LOCATION
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), requestLocation)
            return
        }
        openBackgroundLocationSettingsIfNeeded()
        updateStatus()
    }

    private fun disableBackgroundLocation() {
        AutoCollectionSettings.setBackgroundLocationEnabled(this, false)
        MovementTrackingService.stop(this)
        AutoCollectionSettings.setOnlinePlaceEnrichmentEnabled(this, false)
        onlinePlaceToggle?.isChecked = false
        updateStatus()
    }

    private fun openBackgroundLocationSettingsIfNeeded() {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            pendingPermission = pendingPermission ?: PermissionFlow.BACKGROUND_LOCATION
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), requestBackground)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return
        toast("请在系统权限页面将位置改为“始终允许”")
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun requestInitialPermissionsIfNeeded() {
        if (preferences.getBoolean(initialPermissionGuidanceKey, false)) return
        preferences.edit().putBoolean(initialPermissionGuidanceKey, true).apply()
        if (!hasPermission(Manifest.permission.READ_CALENDAR) || !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            pendingPermission = PermissionFlow.INITIAL
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                requestInitial,
            )
        } else {
            continueInitialPermissionSetup()
        }
    }

    private fun continueInitialPermissionSetup() {
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            AutoCollectionSettings.setBackgroundLocationEnabled(this, true)
        }
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                initialSettingsPage = InitialSettingsPage.BACKGROUND
                toast("请在系统页面将 SELENE 的位置改为“始终允许”")
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } else {
                pendingPermission = PermissionFlow.INITIAL
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), requestBackground)
            }
            return
        }
        openInitialUsageSettings()
    }

    private fun openInitialUsageSettings() {
        if (hasUsageAccess()) return
        initialSettingsPage = InitialSettingsPage.USAGE
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun outputTreeUri(): Uri? = preferences.getString(outputTreeKey, null)?.let(Uri::parse)

    private fun scanPairingCode() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("扫描 Windows SELENE 配对二维码")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    private fun enterPairingCode() {
        val input = EditText(this).apply {
            hint = "粘贴 selene-pair:v1:... 配对码"
            minLines = 4
            setPadding(20, 12, 20, 12)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("输入 Windows 配对码")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("配对") { _, _ -> pairWithCode(input.text.toString()) }
            .show()
    }

    private fun pairWithCode(code: String) {
        toast("正在连接 Windows 配对端…")
        pairingStatus.text = "正在启动同步核心并连接 Windows…"
        PairingManager.pair(this, code) { result ->
            result.onSuccess {
                AutoCollectionSettings.setEnabled(this, true)
                AutoCollectionScheduler.start(this)
                toast("Windows 配对成功，后续会自动采集并同步")
                updateStatus()
            }
                .onFailure {
                    val message = it.message ?: "配对码无效"
                    updateStatus()
                    pairingStatus.text = "配对失败：$message"
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Windows 配对失败")
                        .setMessage(message)
                        .setPositiveButton("确定", null)
                        .show()
                }
            syncMovementTracking()
        }
    }

    private fun resetPairing() {
        if (!PairingManager.isPaired(this)) {
            toast("当前没有已保存的 Windows 配对")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("解除 Windows 配对？")
            .setMessage("只会移除远程信任关系并停止同步，不会删除本机已有快照。")
            .setNegativeButton("取消", null)
            .setPositiveButton("解除") { _, _ ->
                PairingManager.reset(this)
                updateStatus()
                syncMovementTracking()
            }
            .show()
    }

    private fun chooseOutputFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        ), requestOutputTree)
    }

    @Deprecated("Platform activity-result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val scanResult: IntentResult? = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null) {
            if (!scanResult.contents.isNullOrBlank()) pairWithCode(scanResult.contents)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestOutputTree || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags == 0) {
            toast("系统没有授予文件夹读写权限")
            return
        }
        @Suppress("WrongConstant")
        contentResolver.takePersistableUriPermission(uri, flags)
        preferences.edit().putString(outputTreeKey, uri.toString()).apply()
        if (AutoCollectionSettings.isEnabled(this)) AutoCollectionScheduler.start(this)
        updateStatus()
    }

    @Deprecated("Platform permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestBackground) {
            val flow = pendingPermission
            pendingPermission = null
            if (flow == PermissionFlow.INITIAL) openInitialUsageSettings()
            updateStatus()
            return
        }
        if (requestCode == requestInitial) {
            pendingPermission = null
            continueInitialPermissionSetup()
            updateStatus()
            return
        }
        if (requestCode !in setOf(requestCalendar, requestLocation)) return
        val flow = pendingPermission
        pendingPermission = null
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            if (flow == PermissionFlow.BACKGROUND_LOCATION) {
                AutoCollectionSettings.setBackgroundLocationEnabled(this, false)
                backgroundToggle?.isChecked = false
            }
            updateStatus()
            return
        }
        if (flow == PermissionFlow.BACKGROUND_LOCATION) openBackgroundLocationSettingsIfNeeded()
        updateStatus()
    }

    private fun hasPermission(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun syncMovementTracking() {
        val backgroundPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val shouldRun = AutoCollectionSettings.isEnabled(this) &&
            AutoCollectionSettings.backgroundLocationEnabled(this) &&
            ContextOutput.hasOutputTarget(this) &&
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            backgroundPermissionGranted
        if (shouldRun) {
            MovementTrackingService.start(this)
            requestMovementNotificationPermissionIfNeeded()
        } else {
            MovementTrackingService.stop(this)
        }
    }

    private fun requestMovementNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS) ||
            preferences.getBoolean(movementNotificationGuidanceKey, false)
        ) return
        preferences.edit().putBoolean(movementNotificationGuidanceKey, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestNotifications)
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
