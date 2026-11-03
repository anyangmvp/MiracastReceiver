package com.weekd.miracastreceiver.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.discovery.DeviceInfoProvider
import com.weekd.miracastreceiver.discovery.MdnsAdvertiser
import com.weekd.miracastreceiver.service.CastReceiverService
import com.weekd.miracastreceiver.util.AppLanguage
import com.weekd.miracastreceiver.util.AppSettings
import com.weekd.miracastreceiver.utils.NetworkUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import android.widget.TextView

/**
 * 主 Activity - 等待投屏连接
 */
class MainActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(if (newBase != null) AppLanguage.wrap(newBase) else newBase)
    }


    private lateinit var mdnsAdvertiser: MdnsAdvertiser
    private lateinit var deviceInfoProvider: DeviceInfoProvider

    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceIp: TextView
    private lateinit var tvStatus: TextView
    private lateinit var switchAutoStart: SwitchCompat
    private lateinit var switchReturnToApp: SwitchCompat
    private lateinit var tvLanguageValue: TextView

    /** 定位权限弹窗还在时不叠加悬浮窗权限提示，等它有结果再说 */
    private var wifiPermissionPending = false
    private var overlayPromptShown = false

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001

        /**
         * Wi-Fi Direct 需要的危险权限，必须运行时申请。
         *
         * 少了它 `WifiP2pManager.createGroup()` 会抛 SecurityException，Miracast 直接不可用
         * （AirPlay / DLNA 不受影响）。Android 13+ 用 NEARBY_WIFI_DEVICES 取代定位权限。
         */
        private val WIFI_DIRECT_PERMISSIONS: Array<String>
            get() = if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                // Android 12+ 只申请 FINE 会被系统直接拒绝，必须两个一起申请
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initServices()
        initViews()
        // 切换语言会重建本页（savedInstanceState 非空），这时不要把权限弹窗再弹一遍
        if (savedInstanceState == null) {
            requestWifiDirectPermissions()
        } else {
            overlayPromptShown = true
        }
        checkNetworkAndStart()
    }

    private fun requestWifiDirectPermissions() {
        val missing = WIFI_DIRECT_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Timber.i("Requesting Wi-Fi Direct permissions: $missing")
            wifiPermissionPending = true
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return
        wifiPermissionPending = false

        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            // 权限是在服务启动之后才拿到的，重启一次服务让 Wi-Fi Direct 重新初始化
            Timber.i("Wi-Fi Direct permissions granted, restarting cast service")
            startCastService()
        } else {
            Timber.w("Wi-Fi Direct permissions denied — Miracast unavailable")
            tvStatus.text = getString(R.string.status_permission_denied)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!wifiPermissionPending) promptOverlayPermissionIfNeeded()
    }

    /**
     * 「显示在其他应用上层」权限。
     *
     * Android 10 起系统禁止后台启动 Activity，前台服务也不例外。开机自启或者用户按了
     * 主页键之后，应用不在前台，投屏连上时服务拉起播放页会被系统静默拦截 —— 声音、
     * 解码都在跑，屏幕上却什么都没有。持有这个权限的应用可以豁免该限制。
     */
    private fun promptOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (overlayPromptShown || Settings.canDrawOverlays(this)) return
        overlayPromptShown = true

        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_permission_title)
            .setMessage(R.string.overlay_permission_message)
            .setPositiveButton(R.string.overlay_permission_open) { _, _ -> openOverlaySettings() }
            .setNegativeButton(R.string.overlay_permission_later, null)
            .show()
    }

    private fun openOverlaySettings() {
        val withPackage = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        // 部分电视的设置页不认带包名的 Intent，退回到列表页让用户自己找
        val intents = listOf(withPackage, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Timber.w("Overlay settings not available: ${intent.data}")
            }
        }
        Timber.w("No overlay permission settings page on this device")
        Toast.makeText(
            this,
            getString(R.string.overlay_settings_missing, packageName),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun initServices() {
        deviceInfoProvider = DeviceInfoProvider(this)
        mdnsAdvertiser = MdnsAdvertiser(this)
    }

    private fun initViews() {
        tvDeviceName = findViewById(R.id.tv_device_name)
        tvDeviceIp = findViewById(R.id.tv_device_ip)
        tvStatus = findViewById(R.id.tv_status)
        switchAutoStart = findViewById(R.id.switch_auto_start)
        findViewById<SwitchCompat>(R.id.switch_auto_rotate).apply {
            isChecked = AppSettings.isAutoRotateToFit(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AppSettings.setAutoRotateToFit(this@MainActivity, checked) }
        }
        switchReturnToApp = findViewById(R.id.switch_return_to_app)
        tvLanguageValue = findViewById(R.id.tv_language_value)

        // 卡片 1: 改名字
        updateDeviceNameDisplay()
        findViewById<View>(R.id.setting_device_name)?.setOnClickListener {
            showChangeDeviceNameDialog()
        }

        // 卡片 2: 开机启动
        val tvAutoStartLabel = findViewById<TextView>(R.id.tv_auto_start_label)
        switchAutoStart.isChecked = AppSettings.isAutoStartOnBoot(this)
        tvAutoStartLabel?.text = getString(if (switchAutoStart.isChecked) R.string.state_enabled else R.string.state_disabled)
        findViewById<View>(R.id.card_auto_start)?.setOnClickListener {
            val newState = !switchAutoStart.isChecked
            switchAutoStart.isChecked = newState
            AppSettings.setAutoStartOnBoot(this, newState)
            tvAutoStartLabel?.text = getString(if (newState) R.string.state_enabled else R.string.state_disabled)
            Timber.i("Auto start on boot set to $newState")
        }

        // 卡片 3: 平滑播放
        val switchSmooth = findViewById<SwitchCompat>(R.id.switch_smooth_image_transition)
        val tvSmoothLabel = findViewById<TextView>(R.id.tv_smooth_label)
        switchSmooth?.isChecked = AppSettings.isSmoothImageTransition(this)
        tvSmoothLabel?.text = getString(if (switchSmooth?.isChecked == true) R.string.state_enabled else R.string.state_disabled)
        findViewById<View>(R.id.card_smooth_transition)?.setOnClickListener {
            val newState = !(switchSmooth?.isChecked ?: false)
            switchSmooth?.isChecked = newState
            AppSettings.setSmoothImageTransition(this, newState)
            tvSmoothLabel?.text = getString(if (newState) R.string.state_enabled else R.string.state_disabled)
            Timber.i("Smooth image transition set to $newState")
        }

        // 卡片 4: 自动旋转屏幕适配
        val switchAutoRotate = findViewById<SwitchCompat>(R.id.switch_auto_rotate)
        val tvAutoRotateLabel = findViewById<TextView>(R.id.tv_auto_rotate_label)
        switchAutoRotate?.isChecked = AppSettings.isAutoRotateToFit(this)
        tvAutoRotateLabel?.text = getString(if (switchAutoRotate?.isChecked == true) R.string.state_enabled else R.string.state_disabled)
        findViewById<View>(R.id.card_auto_rotate)?.setOnClickListener {
            val newState = !(switchAutoRotate?.isChecked ?: false)
            switchAutoRotate?.isChecked = newState
            AppSettings.setAutoRotateToFit(this, newState)
            tvAutoRotateLabel?.text = getString(if (newState) R.string.state_enabled else R.string.state_disabled)
            Timber.i("Auto rotate to fit set to $newState")
        }

        // 卡片 5: 语言切换 (点击直接在中英文之间来回切换)
        val isEn = AppLanguage.isEnglish(this)
        tvLanguageValue.text = if (isEn) "English" else "简体中文"
        findViewById<View>(R.id.setting_language)?.setOnClickListener {
            val newTag = if (isEn) AppLanguage.TAG_ZH else AppLanguage.TAG_EN
            Timber.i("Toggling language to $newTag")
            AppLanguage.apply(this, newTag)
            recreate()
        }

        // 卡片 6: 退出应用
        findViewById<View>(R.id.card_exit_app)?.setOnClickListener {
            finish()
        }

        // 投屏结束后是否留在本应用
        switchReturnToApp?.isChecked = AppSettings.isReturnToAppAfterCast(this)
        switchReturnToApp?.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setReturnToAppAfterCast(this, isChecked)
        }
    }

    /** 第一项是「跟随系统」，其后是 [AppLanguage.OPTIONS]。 */
    private fun checkNetworkAndStart() {
        val ivTopWifi = findViewById<android.widget.ImageView>(R.id.iv_top_wifi)
        val isWifi = NetworkUtils.isWifiConnected(this)
        ivTopWifi?.visibility = if (isWifi) View.VISIBLE else View.GONE

        if (!NetworkUtils.isNetworkAvailable(this)) {
            tvStatus.text = getString(R.string.status_no_network_four_char)
            Timber.w("Network not available")
            return
        }

        if (!isWifi) {
            tvStatus.text = getString(R.string.status_no_wifi_four_char)
            Timber.w("WiFi not connected")
        }

        // 获取 IP 地址
        val ipAddress = NetworkUtils.getLocalIpAddress(this)
        if (ipAddress != null) {
            tvDeviceIp.text = ipAddress
            Timber.i("Local IP: $ipAddress")
        } else {
            tvDeviceIp.text = "获取中..."
        }

        // 启动服务
        startCastService()
        startAdvertising()

        // 更新状态
        updateStatus()
    }

    private fun startCastService() {
        val intent = Intent(this, CastReceiverService::class.java)
        startService(intent)
        Timber.i("Cast receiver service started")
    }

    private fun startAdvertising() {
        val deviceInfo = deviceInfoProvider.getDeviceInfo()
        val deviceName = deviceInfoProvider.getDeviceName()
        val macAddress = NetworkUtils.getMacAddress()

        // AirPlay is advertised by CastReceiverService/AirPlayReceiver only.
        // Do not register a second _airplay._tcp service here: duplicate AirPlay mDNS
        // records with different TXT capabilities can make iOS connect with the wrong
        // protocol path and never send the mirroring video SETUP.

        // 启动自定义 Miracast 服务广播（用于自定义 Android 发送端）
        mdnsAdvertiser.startAdvertising(
            serviceName = deviceName,
            port = 8080,
            deviceInfo = deviceInfo
        )

        Timber.i("Started AirPlay and Miracast advertising: $deviceName")
    }

    private fun updateStatus() {
        lifecycleScope.launch {
            while (true) {
                if (mdnsAdvertiser.isAdvertising()) {
                    tvStatus.text = getString(R.string.status_ready_four_char)
                } else {
                    tvStatus.text = getString(R.string.status_starting_four_char)
                }
                val ip = NetworkUtils.getLocalIpAddress(this@MainActivity)
                if (ip != null) {
                    tvDeviceIp.text = ip
                }
                delay(2000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mdnsAdvertiser.stopAdvertising()
        Timber.i("MainActivity destroyed")
    }

    private fun updateDeviceNameDisplay() {
        val deviceName = deviceInfoProvider.getDeviceName()
        tvDeviceName.text = deviceName
        findViewById<TextView>(R.id.tv_setting_device_name_val)?.text = getString(R.string.card_current_name_prefix, deviceName)
    }

    
    private fun showCustomDialog(view: View): AlertDialog {
        val dialog = AlertDialog.Builder(this, R.style.Theme_Leanback_Dialog)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        val widthPx = (520 * resources.displayMetrics.density).toInt()
        dialog.window?.setLayout(widthPx, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }

    private fun showChangeDeviceNameDialog() {
        val currentName = deviceInfoProvider.getDeviceName()
        val view = layoutInflater.inflate(R.layout.dialog_change_name, null)
        val et = view.findViewById<android.widget.EditText>(R.id.et_device_name)
        val btnRandom = view.findViewById<android.widget.Button>(R.id.btn_random_name)
        val btnReset = view.findViewById<android.widget.Button>(R.id.btn_reset)
        val btnCancel = view.findViewById<android.widget.Button>(R.id.btn_cancel)
        val btnConfirm = view.findViewById<android.widget.Button>(R.id.btn_confirm)

        val candidateNamesZh = listOf(
            "客厅电视", "主卧电视", "次卧电视", "书房电视", 
            "影音室大屏", "儿童房电视", "餐厅电视", "阳台娱乐屏", "家庭智慧屏"
        )
        val candidateNamesEn = listOf(
            "Living Room TV", "Master Bedroom TV", "Guest Bedroom TV", "Study Room TV",
            "Home Theater", "Kids Room TV", "Dining Room TV", "Family Smart Display"
        )

        et.setText(currentName)
        et.setSelection(currentName.length)

        btnRandom?.setOnClickListener {
            val candidates = if (AppLanguage.isEnglish(this)) candidateNamesEn else candidateNamesZh
            val randomChoice = candidates.filter { it != et.text.toString().trim() }.randomOrNull() ?: candidates.random()
            et.setText(randomChoice)
            et.setSelection(randomChoice.length)
        }

        val dialog = showCustomDialog(view)

        btnReset.setOnClickListener {
            getSharedPreferences("miracast_settings", android.content.Context.MODE_PRIVATE)
                .edit()
                .remove("custom_device_name")
                .apply()
            updateDeviceNameDisplay()
            Toast.makeText(this, getString(R.string.toast_device_name_reset), Toast.LENGTH_SHORT).show()
            restartServices()
            dialog.dismiss()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val newName = et.text.toString().trim()
            if (newName.isNotEmpty()) {
                getSharedPreferences("miracast_settings", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString("custom_device_name", newName)
                    .apply()
                updateDeviceNameDisplay()
                Toast.makeText(this, getString(R.string.toast_device_name_changed, newName), Toast.LENGTH_SHORT).show()
                restartServices()
                dialog.dismiss()
            }
        }

    }

    private fun restartServices() {
        try {
            stopService(Intent(this, CastReceiverService::class.java))
            mdnsAdvertiser.stopAdvertising()
            lifecycleScope.launch {
                kotlinx.coroutines.delay(500)
                startCastService()
                startAdvertising()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error restarting services")
        }
    }
}
