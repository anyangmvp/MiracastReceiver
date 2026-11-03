# MiracastReceiver 深度改造与小米自带投屏适配技术指南

本项目基于开源仓库 [weekdayjast/MiracastReceiver](https://github.com/weekdayjast/MiracastReceiver) 进行深度二次开发与架构重构，旨在打造一款在真实 Android TV / 电视盒子环境下稳定运行、具备影院级现代大屏交互、且全协议兼容的投屏接收端。

---

## 目录
1. [项目改动概述与对比](#1-项目改动概述与对比)
2. [小米手机自带投屏协议深度逆向与适配](#2-小米手机自带投屏协议深度逆向与适配)
3. [核心关键 Bug 诊断与修复实录](#3-核心关键-bug-诊断与修复实录)
4. [全新 Android TV 影院级大屏 UI 重构](#4-全新-android-tv-影院级大屏-ui-重构)
5. [修改与新增文件清单](#5-修改与新增文件清单)
6. [编译、构建与电视部署指南](#6-编译构建与电视部署指南)

---

## 1. 项目改动概述与对比

原版 `MiracastReceiver` 提供了 AirPlay、Miracast 与基础 DLNA 接收的骨架，但在实际电视真机部署和安卓手机投屏生态中存在诸多严重缺陷。本次重构针对协议层、控制层、渲染层以及用户交互层进行了全面升级：

| 模块 / 特性 | 原版 (Upstream) | 改造后版本 (Current) |
| :--- | :--- | :--- |
| **小米相册/视频系统投屏** | ❌ 无法识别（图片误当视频导致崩溃，URL动态无后缀被忽略） | ✅ **完全兼容**（深度解析 DIDL-Lite 元数据与流缓冲） |
| **投屏自动中断 Bug** | ❌ 播放图片 3~4 秒后后台 WFD 误发广播导致被强退 | ✅ **彻底修复**（会话状态鉴别 + 广播源隔离过滤） |
| **图片切换体验** | ⚠️ 切图必黑屏、且转圈 loading 易卡死或退出 | ✅ **平滑双缓冲切换**（后台静默预载，成功即瞬切） |
| **主界面交互与美学** | ⚠️ 简陋移动端风格，大量留白，缺乏大屏沉浸感 | ✅ **现代影院级玻璃态 UI**，科技雪山大屏背景与焦点卡片 |
| **电视遥控器交互** | ⚠️ 原生手机弹窗偏小，D-Pad 焦点难以移动，甚至递归崩溃 | ✅ **Leanback 电视专属弹窗**，自适应大屏尺寸，极佳焦点动效 |
| **设备改名与随机命名** | ⚠️ 只读系统名，无法自由编辑 | ✅ **弹窗重命名 + 一键随机生成常用电视名称** |
| **语言切换** | ⚠️ 10 种语言多层列表弹窗，在部分 Android TV 上无法生效 | ✅ **中/英 1 键瞬时切换**，多级 Configuration 深度注入 |
| **状态信息展示** | ⚠️ 文案冗长且有假图标干扰 | ✅ **精炼 4 字就绪状态 + 醒目大号设备名称 + 真实内网 IP** |

---

## 2. 小米手机自带投屏协议深度逆向与适配

小米 MIUI / HyperOS 系统相册与系统投屏功能是基于 UPnP/DLNA 规范扩展实现的，但小米在实现上有诸多特殊的工程处理，导致标准的通用 DLNA 接收端无法直接工作。以下为针对小米自带投屏的深度逆向与适配原理：

### 2.1 SSDP 服务发现与大小写兼容
- **问题分析**：小米手机在局域网内搜索投屏设备时，会广播 SSDP `M-SEARCH` 包并向接收端的 HTTP 服务请求 `description.xml`。小米发送的 HTTP POST 控制请求中，`SOAPACTION` 请求头的大小写常常不符合标准驼峰格式（例如发送 `Soapaction` 或 `soapaction`）。
- **原版缺陷**：原版 `UpnpHttpServer.kt` 使用普通的 `mutableMapOf<String, String>()` 存储 Headers，并通过 `headers["SOAPACTION"] ?: headers["SOAPAction"]` 获取。只要大小写不匹配，取出的 Action 即为 `null`，导致请求直接返回 400 Bad Request。
- **解决方案**：
  在 `UpnpHttpServer.kt` 中改用大小写不敏感的 `TreeMap`：
  ```kotlin
  val headers = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
  ```
  同时支持容错提取：
  ```kotlin
  val rawAction = headers["SOAPACTION"] ?: headers["SOAPAction"] ?: headers["Soapaction"]
  ```

### 2.2 SOAP 动作兜底解析 (extractActionFromBody)
- **问题分析**：小米相册投屏在发起 `SetAVTransportURI` 或 `Play` 时，部分 MIUI 版本的请求头并未包含标准的 `#SetAVTransportURI` 命名空间后缀，或者直接省略了双引号。
- **解决方案**：
  在 `SoapParser` 中增加了请求体 XML 自动萃取机制。如果 HTTP 请求头没有解析到有效的动作，直接正则提取 XML `<s:Body>` 内部的第一个指令标签作为动作名：
  ```kotlin
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
  ```

### 2.3 动态无后缀 URL 与 DIDL-Lite 元数据识别
- **问题分析**：
  1. 小米系统相册投屏时，手机端会在随机高位端口（如 `http://192.168.0.95:48530/photo/12345` 或带有 MD5 签名的路径）临时拉起微型 HTTP 静态服务器。
  2. 这个 URL **完全没有 `.jpg`、`.jpeg` 或 `.png` 等常见扩展名**。
  3. 原版通过 `url.endsWith(".jpg")` 判断媒体类型，导致该 URL 被归类为视频，传递给 ExoPlayer。ExoPlayer 尝试加载并解析视频音轨轨表，抛出 `UnrecognizedInputFormatException` 严重异常，投屏播放器直接闪退退出。
- **解决方案**：
  在 `DlnaMediaRenderer.kt` 中实现了多维度的媒体类型侦测器 `isImageMedia`：
  1. **检查 DIDL-Lite 元数据**：检查 XML 中的 `upnp:class` 是否包含 `imageItem`、`object.item.imageItem`，或 MIME 类型是否包含 `image/jpeg`、`image/png`、`image/webp` 等。
  2. **URL 特征路径匹配**：针对无扩展名的动态 URL，匹配 `/photo/`、`/image/`、`/dms/image`、`/gallery/` 等特征。
  3. **显式传参**：在广播启动 `PlayerActivity` 时通过 Intent 携带 `EXTRA_IS_IMAGE = true`，强制路由到图片专用渲染管线。

### 2.4 流式字节缓冲解码 (Network Stream to ByteArray)
- **问题分析**：原版使用 `BitmapFactory.decodeStream(URL(uri).openStream())`。在小米手机作为 HTTP 发送端时，其输出的 chunked 流在 Android 电视端网络层不支持 `mark` 和 `reset`，一旦遇到微小网络波动，`decodeStream` 就会静默返回 `null`，导致投屏界面一直处于 loading 或黑屏状态。
- **解决方案**：
  在 `PlayerActivity.kt` 中，采用连接超时控制与全量字节读取缓冲方案：
  ```kotlin
  val bytes = if (uri.startsWith("http://") || uri.startsWith("https://")) {
      val conn = URL(uri).openConnection()
      conn.connectTimeout = 10000
      conn.readTimeout = 15000
      conn.getInputStream().use { it.readBytes() }
  } else {
      contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
  }
  if (bytes != null && bytes.isNotEmpty()) {
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
  }
  ```
  从根本上杜绝了图片流解码失败的问题。

---

## 3. 核心关键 Bug 诊断与修复实录

### 3.1 投屏图片 3~4 秒后自动退出问题
- **故障现象**：无论是小米手机还是其他设备，投屏一张图片后，电视端正常显示了图片，但仅仅维持 3~4 秒，应用就自动关闭并返回主界面。
- **根本原因排查**：
  1. `WfdServer.kt`（Miracast 服务端）内部跑了一个后台轮询协程 `dialSource()`，每隔 3 秒扫描尝试连接 P2P 网段中的 Miracast 发送端。
  2. 探测结束或探测失败时，`WfdSessionHandler` 执行其 `finally` 块，无条件调用了：
     ```kotlin
     onStreamStop?.invoke()
     ```
  3. 该回调导致 `CastReceiverService` 发送系统全局广播 `ACTION_STOP`。
  4. 此时前台正在展示图片的 `PlayerActivity` 接收到了这个全局 `ACTION_STOP` 广播，误以为当前投屏被用户终止，调用了 `exitPlayer()` 销毁了自己。
- **彻底修复方案**：
  1. **会话校验**：在 `WfdSessionHandler.kt` 中增加校验，只有真正建立了有效投屏连接（`sessionId.isNotEmpty()`）的会话关闭时才允许发出 `onStreamStop`。
  2. **广播源标记**：在 `CastReceiverService.kt` 中给 Miracast 产生的 `ACTION_STOP` 附带 `intent.putExtra("source", "miracast")` 标记。
  3. **前台接收端过滤**：在 `PlayerActivity.kt` 的广播接收器中判断：
     ```kotlin
     val src = intent?.getStringExtra("source")
     if (src == "miracast" && !isMiracastSession) {
         Timber.d("Ignoring miracast ACTION_STOP in non-miracast session")
     } else {
         stopPlayback()
         exitPlayer()
     }
     ```

### 3.2 图片平滑播放 (Smooth Image Transition) 体验优化
- **故障现象**：开启平滑切换后，切图时先闪一下转圈 loading 然后黑屏退出。
- **修复方案**：
  1. 引入双缓冲逻辑：当开启平滑模式且当前屏幕已有图片展示时，**绝不重置或隐藏现有的 ImageView**，保持上一张图片静止显示。
  2. 协程在后台异步下载解码新图片。
  3. 新图片成功构建出 `Bitmap` 之后，直接瞬时替换 `imageView.setImageBitmap(bitmap)`，实现类似 PPT 幻灯片般的平滑切图，完全消除了中间令人不适的黑屏与转圈闪烁。

---

## 4. 全新 Android TV 影院级大屏 UI 重构

为贴合 65~85 寸电视的远场（10-foot）交互场景，我们彻底推翻了原版移动端的简陋布局，设计了现代轻奢视觉系统：

### 4.1 视觉与布局革新
- **沉浸式壁纸**：引入暗色科技风壁纸（`bg_main_artwork.jpg`），大屏深蓝底色与雪山晨曦交相辉映，杜绝纯黑死板。
- **精炼就绪状态胶囊**：
  - 顶部左侧展示品牌标识与副标题。
  - 核心焦点区域设置发光指示药丸：`● 等待投屏  |  IP: 192.168.0.98`，文案极简，信息一目了然。
  - 胶囊上方放置 22sp 冰蓝色发光大号字体展示当前**设备名称**。
- **清除假数据**：移除无实际功能的虚假时间与 Wi-Fi 图标，保证电视界面信息的绝对真实。

### 4.2 六宫格功能控制面板（遥控器 D-Pad 完美交互）
设计了 6 块半透明磨砂亚克力微光卡片，每个卡片拥有独立的遥控器焦点发光与缩放态：
1. **改名字**：自定义电视名称，点击弹出大屏优化输入框。
2. **开机启动**：一键切换电视开机是否自动拉起服务。
3. **平滑播放**：一键控制图片投屏是否开启无缝双缓冲切换。
4. **画面旋转**：一键控制大屏是否自适应旋转。
5. **切换语言**：极简的一键中英双语切换，无需选择直接循环轮换。
6. **退出应用**：优雅释放资源并退出回电视原生 Launcher。

### 4.3 专属电视弹窗 (Leanback Dialog) 与随机名称功能
- **自适应窗口**：针对电视屏幕分辨率自动设定为 `520dp` 宽度，采用高对比度暗色微光背景（`bg_tv_dialog.xml`），告别原生 Android 弹窗偏小变形的问题。
- **一键随机名称**：针对电视打字不便的痛点，在改名弹窗中新增「🎲 随机名称」快捷按钮，预设精选的电视位置名称库：
  - 中文预设：`客厅电视`、`卧室电视`、`书房电视`、`家庭影院`、`会议室大屏`、`主卧电视`、`儿童房电视`、`大厅展示屏`
  - 英文预设：`Living Room TV`、`Bedroom TV`、`Study Room TV`、`Home Cinema`、`Meeting Room Display`、`Master Bedroom TV`

---

## 5. 修改与新增文件清单

### 5.1 核心逻辑修改清单
- `app/src/main/java/com/weekd/miracastreceiver/dlna/DlnaMediaRenderer.kt`
  - 新增 `isImageMedia()`，针对小米无后缀动态 URL 及 DIDL-Lite 元数据多维判断。
  - 新增 `extractActionFromBody()` 提取 SOAP XML Body 指令标签兜底。
  - 优化 `extractAction()` 支持无双引号与不同前缀容错。
- `app/src/main/java/com/weekd/miracastreceiver/dlna/UpnpHttpServer.kt`
  - HTTP Header 采用大小写不敏感存储与提取，全面兼容小米请求头。
  - 解析失败时调用 `extractActionFromBody` 兜底。
- `app/src/main/java/com/weekd/miracastreceiver/miracast/WfdServer.kt`
  - 增加 TCP ServerSocket 监听 7236 端口，支持手机端作为客户端主动发起 RTSP 协商。
  - 增加 `sessionLock` 避免多重会话并发冲突。
- `app/src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt`
  - 增加 `sessionId.isNotEmpty()` 守卫，杜绝无效扫描关闭误发 `onStreamStop`。
- `app/src/main/java/com/weekd/miracastreceiver/service/CastReceiverService.kt`
  - 发送 Miracast 停止广播时附带 `source = "miracast"` 标记。
- `app/src/main/java/com/weekd/miracastreceiver/ui/PlayerActivity.kt`
  - 增加广播源识别过滤，忽略非本会话的 `ACTION_STOP`。
  - 支持 `EXTRA_IS_IMAGE` 显式路由。
  - 实现图片全量字节流读取缓冲（`readBytes()` + `decodeByteArray`）。
  - 实现平滑播放双缓冲静默切换机制。
- `app/src/main/java/com/weekd/miracastreceiver/ui/MainActivity.kt`
  - 彻底重构 UI 绑定逻辑，适配 6 宫格卡片遥控器按键交互。
  - 实现电视专用 Leanback 自定义弹窗，支持随机改名与 D-Pad 导航。
  - 实现中英双语一键瞬切并重构界面。
- `app/src/main/java/com/weekd/miracastreceiver/util/AppLanguage.kt`
  - 实现深度注入 `Configuration` 与 `LocaleList`，保证电视系统即时刷新语言环境。
- `app/src/main/java/com/weekd/miracastreceiver/discovery/DeviceInfoProvider.kt`
  - 优先读取应用内自定义设备名称，并同步生效至 mDNS、AirPlay 与 DLNA。

### 5.2 UI 与资源新增清单
- `app/src/main/res/layout/activity_main.xml` (及 `layout-sw480dp-land`, `layout-sw600dp-land`)：现代化 10-foot 大屏布局。
- `app/src/main/res/layout/dialog_change_name.xml`：电视改名专属弹窗布局（含随机按钮）。
- `app/src/main/res/drawable-nodpi/bg_main_artwork.jpg`：高分辨率影院级背景图。
- `app/src/main/res/drawable/bg_dashboard_card.xml`：磨砂亚克力玻璃卡片与焦点动效。
- `app/src/main/res/drawable/bg_status_pill.xml`：就绪状态高亮胶囊背景。
- `app/src/main/res/drawable/bg_tv_dialog.xml`：电视弹窗半透明暗色背景。
- `app/src/main/res/values/strings.xml` 与 `values-zh/strings.xml`：全量中英文双语资源定义。

---

## 6. 编译构建与电视部署指南

### 6.1 本地编译要求
- Android Studio Hedgehog (2023.1.1) 或更新版本。
- Android SDK 34，NDK 25.1.8937393（编译 C/C++ RTSP 与音视频解码组件）。
- JDK 17。

### 6.2 编译打包 Release / Debug APK
在项目根目录运行 Gradle 构建：
```bash
# Windows PowerShell
./gradlew assembleDebug

# 生成 APK 路径：
# app/build/outputs/apk/debug/app-debug.apk
```

### 6.3 通过 ADB 部署到智能电视
确保电视已开启「开发者选项」和「ADB 调试」，并通过 Wi-Fi 连接电视 IP：
```bash
# 1. 连接电视
adb connect 192.168.0.98:5555

# 2. 安装 APK（-r 覆盖安装）
adb -s 192.168.0.98:5555 install -r app/build/outputs/apk/debug/app-debug.apk

# 3. 启动应用
adb -s 192.168.0.98:5555 shell am start -n com.weekd.miracastreceiver/.ui.MainActivity
```
安装完成后，在同一局域网下打开小米手机相册，点击任意照片/视频中的「投屏」按钮，选择你的电视名称，即可秒级连接投屏！
