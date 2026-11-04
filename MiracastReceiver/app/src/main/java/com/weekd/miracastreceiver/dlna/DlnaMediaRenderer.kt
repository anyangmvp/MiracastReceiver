package com.weekd.miracastreceiver.dlna

import timber.log.Timber
import java.util.regex.Pattern

/**
 * DLNA 媒体渲染器状态管理
 */
class DlnaMediaRenderer {

    enum class TransportState {
        STOPPED,
        PLAYING,
        PAUSED_PLAYBACK,
        TRANSITIONING,
        NO_MEDIA_PRESENT
    }

    data class MediaState(
        var uri: String = "",
        var metadata: String = "",
        var transportState: TransportState = TransportState.NO_MEDIA_PRESENT,
        var volume: Int = 50,
        var muted: Boolean = false,
        var position: Long = 0L,
        var duration: Long = 0L,
        var playlist: List<String> = emptyList(),
        var currentIndex: Int = 0,
        var playbackSpeed: Float = 1f
    )

    private val state = MediaState()

    var onSetUri: ((String, String) -> Unit)? = null
    var onPlay: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null
    var onSpeedChanged: ((Float) -> Unit)? = null
    var onQualityUriChanged: ((String) -> Unit)? = null
    var onVolumeChanged: ((Int) -> Unit)? = null
    var onMuteChanged: ((Boolean) -> Unit)? = null

    fun setAVTransportURI(uri: String, metadata: String) {
        state.uri = decodeXmlEntities(uri)
        state.metadata = metadata
        state.playlist = parsePlaylist(state.uri, metadata)
        state.currentIndex = state.playlist.indexOf(state.uri).takeIf { it >= 0 } ?: 0
        state.transportState = TransportState.STOPPED
        Timber.i("DLNA SetAVTransportURI: ${state.uri}, playlist size=${state.playlist.size}")
        onSetUri?.invoke(state.uri, metadata)
    }

    fun isImageMedia(uri: String, metadata: String): Boolean {
        // 1. 检查 DIDL-Lite metadata 中的 upnp:class 和 mime 声明（针对小米等动态 URL 无后缀的相册投屏）
        if (metadata.contains("imageItem", ignoreCase = true) ||
            metadata.contains("image/jpeg", ignoreCase = true) ||
            metadata.contains("image/jpg", ignoreCase = true) ||
            metadata.contains("image/png", ignoreCase = true) ||
            metadata.contains("image/webp", ignoreCase = true) ||
            metadata.contains("image/gif", ignoreCase = true) ||
            metadata.contains("image/bmp", ignoreCase = true) ||
            metadata.contains(":image:", ignoreCase = true) ||
            metadata.contains("photo", ignoreCase = true) ||
            metadata.contains("picture", ignoreCase = true) ||
            metadata.contains("object.item.imageItem", ignoreCase = true)) {
            return true
        }

        // 2. 检查常见图片文件后缀
        val clean = uri.substringBefore('?').lowercase()
        if (clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") ||
            clean.endsWith(".gif") || clean.endsWith(".webp") || clean.endsWith(".bmp") ||
            clean.endsWith(".heic") || clean.endsWith(".heif")) {
            return true
        }

        // 3. 检查小米相册等特定路径特征
        if (clean.contains("/photo/") || clean.contains("/image/") ||
            clean.contains("/dms/image") || clean.contains("/gallery/")) {
            return true
        }

        return false
    }

    fun play() {
        if (state.uri.isNotBlank()) {
            state.transportState = TransportState.PLAYING
            Timber.i("DLNA Play")
            onPlay?.invoke()
        }
    }

    fun pause() {
        state.transportState = TransportState.PAUSED_PLAYBACK
        Timber.i("DLNA Pause")
        onPause?.invoke()
    }

    fun stop() {
        state.transportState = TransportState.STOPPED
        Timber.i("DLNA Stop")
        onStop?.invoke()
    }

    fun seek(target: String) {
        val position = parseTimeToMs(target)
        state.position = position
        Timber.i("DLNA Seek: $target -> $position ms")
        onSeek?.invoke(position)
    }

    fun setPlaybackSpeed(speed: String) {
        val value = speed.toFloatOrNull()?.coerceIn(0.25f, 4f) ?: 1f
        state.playbackSpeed = value
        Timber.i("DLNA SetSpeed: $value")
        onSpeedChanged?.invoke(value)
    }

    fun setQualityUri(uri: String) {
        val decoded = decodeXmlEntities(uri)
        if (decoded.isNotBlank()) {
            state.uri = decoded
            Timber.i("DLNA SetQualityURI: $decoded")
            onQualityUriChanged?.invoke(decoded)
        }
    }

    fun setVolume(volume: Int) {
        state.volume = volume.coerceIn(0, 100)
        Timber.i("DLNA SetVolume: ${state.volume}")
        onVolumeChanged?.invoke(state.volume)
    }

    fun setMute(muted: Boolean) {
        state.muted = muted
        Timber.i("DLNA SetMute: $muted")
        onMuteChanged?.invoke(muted)
    }

    fun getState(): MediaState = state.copy()

    fun setPosition(positionMs: Long, durationMs: Long) {
        state.position = positionMs
        state.duration = durationMs
    }

    fun updatePosition(positionMs: Long, durationMs: Long) = setPosition(positionMs, durationMs)

    fun setPlaying() {
        state.transportState = TransportState.PLAYING
    }

    fun setStopped() {
        state.transportState = TransportState.STOPPED
    }

    fun setPaused() {
        state.transportState = TransportState.PAUSED_PLAYBACK
    }

    private fun parseTimeToMs(time: String): Long {
        return try {
            val parts = time.split(":")
            if (parts.size == 3) {
                val hours = parts[0].toLong()
                val minutes = parts[1].toLong()
                val seconds = parts[2].toDouble()
                ((hours * 3600 + minutes * 60 + seconds) * 1000).toLong()
            } else {
                0L
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing time: $time")
            0L
        }
    }

    private fun decodeXmlEntities(value: String): String {
        return value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun parsePlaylist(currentUri: String, metadata: String): List<String> {
        val uris = mutableListOf<String>()
        // 如果有播放列表元数据，尝试解析
        if (metadata.contains("<container") || metadata.contains("playlist", ignoreCase = true)) {
            // 简单解析 DIDL-Lite 中的多个 item
            val itemPattern = Pattern.compile("<item[^>]*>.*?<res[^>]*>([^<]+)</res>.*?</item>", Pattern.DOTALL)
            val matcher = itemPattern.matcher(metadata)
            while (matcher.find()) {
                matcher.group(1)?.trim()?.let { uri ->
                    if (uri.isNotBlank()) uris.add(decodeXmlEntities(uri))
                }
            }
        }
        // 如果没有找到播放列表，至少包含当前 URI
        return if (uris.isEmpty()) listOf(currentUri) else uris
    }
}

/**
 * SOAP 请求解析工具
 */
object SoapParser {
    fun extractAction(soapAction: String?): String {
        if (soapAction.isNullOrBlank()) return ""
        val cleaned = soapAction.trim().trim('"').trim()
        val action = if (cleaned.contains('#')) {
            cleaned.substringAfterLast('#').trim()
        } else if (cleaned.contains(':') && !cleaned.startsWith("http", ignoreCase = true)) {
            cleaned.substringAfterLast(':').trim()
        } else {
            cleaned
        }
        return action
    }

    fun extractActionFromBody(body: String): String {
        if (body.isBlank()) return ""
        val pattern = Pattern.compile("<(?:[a-zA-Z0-9_]+:)?([A-Za-z0-9_]+)(?:\\s+[^>]*)?>", Pattern.DOTALL)
        val bodyIndex = body.indexOf(":Body", ignoreCase = true).takeIf { it >= 0 }
            ?: body.indexOf("<Body", ignoreCase = true)
        val searchArea = if (bodyIndex >= 0) body.substring(bodyIndex) else body
        val matcher = pattern.matcher(searchArea)
        while (matcher.find()) {
            val tag = matcher.group(1) ?: ""
            if (!tag.equals("Body", ignoreCase = true) && !tag.equals("Envelope", ignoreCase = true)) {
                return tag
            }
        }
        return ""
    }

    fun extractTagValue(xml: String, tagName: String): String {
        // 尝试多种模式匹配，提高兼容性
        val patterns = listOf(
            // 标准格式：<tagName>value</tagName>
            Pattern.compile("<$tagName>(.*?)</$tagName>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE),
            // 命名空间格式：<prefix:tagName>value</prefix:tagName>
            Pattern.compile("<[^:>]+:$tagName>(.*?)</[^:>]+:$tagName>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE),
            // CDATA 格式：<tagName><![CDATA[value]]></tagName>
            Pattern.compile("<$tagName><!\\[CDATA\\[(.*?)\\]\\]></$tagName>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE),
            // 属性格式（有些客户端把值放在属性里）
            Pattern.compile("<$tagName[^>]*>\\s*<!\\[CDATA\\[(.*?)\\]\\]>\\s*</$tagName>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(xml)
            if (matcher.find()) {
                val value = matcher.group(1)?.trim().orEmpty()
                if (value.isNotEmpty()) {
                    return value
                }
            }
        }

        // 如果都没匹配到，尝试直接搜索标签名（不区分大小写）
        val fallbackPattern = Pattern.compile("<[^>]*${tagName}[^>]*>(.*?)</[^>]*>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val fallbackMatcher = fallbackPattern.matcher(xml)
        if (fallbackMatcher.find()) {
            return fallbackMatcher.group(1)?.trim().orEmpty()
        }

        return ""
    }

    fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}
