package com.weekd.miracastreceiver.discovery

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.weekd.miracastreceiver.utils.CodecUtils
import com.weekd.miracastreceiver.utils.NetworkUtils

/**
 * 设备信息提供器
 */
class DeviceInfoProvider(private val context: Context) {

    /**
     * 对外显示的设备名，AirPlay / DLNA / Miracast 共用。
     *
     * 1. 优先取用户在应用内自定义设置的名字
     * 2. 其次取系统设置里的设备名称（Settings.Global `device_name`，即电视「设置 → 关于 → 设备名称」）
     * 3. 取不到时退回「厂商 型号」
     */
    fun getDeviceName(): String {
        val prefs = context.getSharedPreferences("miracast_settings", Context.MODE_PRIVATE)
        val customName = prefs.getString("custom_device_name", null)
        if (!customName.isNullOrBlank()) {
            return customName.trim()
        }

        val systemName = try {
            Settings.Global.getString(context.contentResolver, "device_name")
        } catch (e: Exception) {
            null
        }
        val base = systemName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Android TV" }
        return base
    }

    fun getDeviceId(): String {
        return "${Build.MANUFACTURER}-${Build.MODEL}-${Build.SERIAL}"
            .replace(" ", "-")
            .replace("/", "-")
    }

    fun getDeviceInfo(): Map<String, String> {
        val videoConfig = CodecUtils.getRecommendedVideoConfig()

        return mapOf(
            "name" to getDeviceName(),
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "android_version" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "ip" to (NetworkUtils.getLocalIpAddress(context) ?: "unknown"),
            "h264" to CodecUtils.isVideoDecoderSupported(CodecUtils.MIME_VIDEO_H264).toString(),
            "h265" to CodecUtils.isVideoDecoderSupported(CodecUtils.MIME_VIDEO_H265).toString(),
            "recommended_codec" to videoConfig.mimeType,
            "max_width" to videoConfig.width.toString(),
            "max_height" to videoConfig.height.toString(),
            "max_fps" to videoConfig.frameRate.toString()
        )
    }
}
