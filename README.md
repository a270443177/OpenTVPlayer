# OpenTV Player

一个**原创**的 Android IPTV 播放器，同时适配 **Android TV（遥控器）** 与 **手机 / 平板（触屏）**。

> 本项目为从零编写的独立实现，使用公开协议（M3U / Xtream Codes API / XMLTV / HLS）与开源库（AndroidX、Media3/ExoPlayer）。
> 不包含任何第三方商业应用的代码或资源。

[![Build](https://github.com/a270443177/OpenTVPlayer/actions/workflows/build.yml/badge.svg)](https://github.com/a270443177/OpenTVPlayer/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-green.svg)](#环境要求)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-orange.svg)](#技术栈)

---

## 下载

| 版本 | 说明 |
|---|---|
| [**最新正式包（Releases）**](https://github.com/a270443177/OpenTVPlayer/releases/latest) | 正式签名 APK，可直接安装到电视 / 手机 |

> CI 每次提交都会自动构建 APK，可在 [Actions](https://github.com/a270443177/OpenTVPlayer/actions) 页面下载构建产物（未签名）。

---

## 功能

### 播放列表与频道

- **M3U / M3U8** 链接解析；支持 `tvg-chno`（频道号）、`tvg-logo`（台标）、`group-title`（分组）
- **Xtream Codes**（`player_api.php`）账号加载：直播 / 分类自动同步
- **本地文件导入**：通过系统文件选择器（SAF）导入手机或 U 盘里的 m3u 文件
- 自动分组，分组筛选（全部 / ★ 收藏 / 具体分组）
- **频道名批量清理**：自动去掉「高清 / 4K / H265 / 序号」等前后缀（可关）
- 频道排序：列表顺序 / 按频道号 / 按名称
- **搜索**：支持频道名搜索，可切换「收藏优先」「同时搜节目描述」

### 播放

- **Media3 / ExoPlayer** 内核，支持 HLS（`.m3u8`）、HTTP 直链
- **数字键直选频道**：遥控器按 `1`、`2` → 自动跳到 12 台，屏幕显示输入与匹配台名；确定键立即跳转、返回键撤销
- **频道循环切换**：最后一个频道再往下回到第一个（可关）
- 上下键换台方向可切换（上 = 下一个 / 上一个）
- **播放速度** 0.5x ~ 2.0x 六档
- **画中画（PiP）**：手机按 Home 键自动进入小窗，小窗内确定/返回回全屏
- 画面比例：适应画面 / 拉伸填满 / 裁切填充 / 铺满屏幕 / 固定宽度
- 解码器偏好：自动 / 优先硬件 / 优先软件
- **音频直通**：把 AC3/EAC3 等压缩音频交给电视或功放解码（老盒子「有画面没声音」可试开）
- **AFR 自动帧率**：按视频帧率切换屏幕刷新率，减少画面抖动（仅物理 HDMI 显示有效）
- 音轨 / 字幕轨道选择；缓冲时长可调
- **画面内时钟**：位置（四角）与字号可调
- 睡眠定时器；保持屏幕常亮

### EPG 与回看

- **多 EPG 源**管理：可添加多个 XMLTV 源并分别命名
- **EPG 时间偏移**：修正时区 / 源时间错误
- 支持 gzip 压缩的 XMLTV 源自动解压
- 频道下方显示「正在播出 / 接下来」
- **完整节目单页**（Guide）：按时间轴横竖滚动浏览
- **回看（Catch-up）**：即使播放列表没有 catchup 属性也能回看（自动推断归档地址）
- **节目提醒**：到点提醒即将播出的节目

### 网络与源

- **UDP 组播支持**：内置 `MulticastSocket` → 本地 HTTP 代理，可直接播放 `udp://@239.x.x.x:xxxx` 组播源
- **外部 udpxy**：也可配置 udpxy 地址转换组播
- **外部播放器**：可把当前频道交给系统里的其他播放器打开
- 自定义 User-Agent；明文 HTTP 支持（IPTV 源普遍为 http）
- 播放列表自动定时刷新

### 电视与遥控

- 完整 **D-pad 焦点**导航，`LEANBACK_LAUNCHER` 入口 + TV banner
- 播放页**控制面板 5 秒自动收起**，切台时不打扰画面
- 切台角标显示频道号与台名
- **开机自启**（需要系统「后台弹出界面 / 自启动」权限）
- **家长控制**：PIN 码锁定频道与播放列表管理
- 内置**遥控器按键说明**（设置页可随时查看）

## 技术栈

- Kotlin 1.9.24 · Android Gradle Plugin 8.5.2 · Gradle 8.7 · JDK 17
- `compileSdk 34` / `targetSdk 34` / `minSdk 21`（Android 5.0+）
- AndroidX：appcompat、recyclerview、constraintlayout、core-ktx
- Media3 / ExoPlayer 1.3.1（`media3-exoplayer` + `media3-exoplayer-hls` + `media3-ui`）
- `androidx.profileinstaller` + 手写 `baseline-prof.txt` 加速冷启动
- 网络：`HttpURLConnection`（无额外依赖）；JSON：`org.json`
- 持久化：播放列表存 `filesDir/playlists.json`，设置存 `SharedPreferences`
  （刻意不引入 Room/KSP，降低构建复杂度）

## 环境要求

- JDK 17 或更高
- Android SDK：`platform-tools`、`platforms;android-34`、`build-tools;34.0.0`
- 构建验证平台：Windows（Gradle 8.7）；Linux / macOS 同样可用

## 构建

```bash
# 1) 指定 SDK 路径（在工程根目录创建 local.properties）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2) 构建 Debug 版
./gradlew assembleDebug

# 3) 构建 Release 版（需要先配置签名，见下）
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/{debug,release}/`

### Release 签名

签名信息从工程根目录的 `keystore.properties` 读取，该文件**已被 .gitignore 排除**，不会提交。
先复制模板再填入自己的信息：

```bash
cp keystore.properties.example keystore.properties
# 生成密钥（JDK 自带 keytool）
keytool -genkeypair -v -keystore my-release.jks -alias opentv \
  -keyalg RSA -keysize 2048 -validity 10950
```

`keystore.properties` 内容：

```properties
storeFile=my-release.jks
storePassword=你的密钥库密码
keyAlias=opentv
keyPassword=你的密钥密码
```

> 未配置 `keystore.properties` 时，`assembleRelease` 仍可构建，但产出的是**未签名** APK。
> 正式分发请务必备份 `.jks` 文件——后续升级必须用同一密钥签名，否则无法覆盖安装。

## 安装

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

电视上也可以把 APK 拷到 U 盘，用电视自带文件管理器安装（需在系统设置里允许「安装未知来源应用」）。

## 使用

1. 打开应用，点右上角 **「添加播放列表」**。
2. 选择 **M3U 链接**、**Xtream Codes**（填服务器 / 用户名 / 密码）或**本地文件**。
3. 可选填 **EPG 地址**（XMLTV），加载后频道下方会显示节目信息。
4. 点击频道即可播放；**长按确定**打开频道选项（收藏 / 频道信息 / 复制地址 / 外部播放器）。
5. 遥控器按键说明：设置 → 遥控器 → **按键说明**。

## 目录结构

```
OpenTVPlayer/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── keystore.properties.example    签名配置模板（真实配置不入库）
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── baseline-prof.txt         启动性能热点（Baseline Profile）
        ├── java/com/opentv/player/
        │   ├── MainActivity.kt           频道列表 / 分组 / 收藏 / 播放列表管理
        │   ├── PlayerActivity.kt         Media3 播放页（数字键跳台 / 循环换台 / AFR）
        │   ├── GuideActivity.kt          节目单页
        │   ├── SearchActivity.kt         搜索
        │   ├── SettingsActivity.kt       设置
        │   ├── BootReceiver.kt           开机自启
        │   ├── data/
        │   │   ├── Models.kt             Channel / Playlist / JSON 序列化
        │   │   ├── Library.kt            播放列表聚合读取
        │   │   ├── M3uParser.kt          M3U / M3U8 解析
        │   │   ├── XtreamClient.kt       Xtream Codes API
        │   │   ├── Epg.kt / EpgSources.kt XMLTV 解析与多源管理
        │   │   ├── Catchup.kt            回看地址推断
        │   │   ├── NameCleaner.kt        频道名清理
        │   │   ├── PlaybackPrefs.kt      播放与界面偏好
        │   │   ├── Reminders.kt          节目提醒
        │   │   ├── Http.kt               极简 HTTP 客户端
        │   │   └── PlaylistRepository.kt 本地持久化 + 收藏
        │   ├── net/
        │   │   ├── MulticastProxy.kt     UDP 组播 → 本地 HTTP 代理
        │   │   └── StreamResolver.kt     流地址解析
        │   └── ui/                       列表适配器、台标加载、通用 UI 工具
        └── res/                          布局、主题、图标、banner
```

## 说明与边界

- 本应用只提供播放器能力，**不附带任何频道源**；使用哪种内容源由使用者自行决定并自负合规责任。
- 明文 HTTP 已放开（`usesCleartextTraffic="true"`），因为 IPTV 源普遍使用 http。
- **AFR 与音频直通**的实际效果依赖电视硬件与片源，模拟器上无法验证；如遇画面或声音异常，可在设置中关闭对应开关。
- 部分电视 ROM（如模拟器 / 部分定制 ROM）不向第三方应用分发 `BOOT_COMPLETED` 广播，此时开机自启不会生效，属系统限制。
- 尚未实现：录制（依赖运营商私有流窗口机制，未做）、RTMP 协议、SMB 网络共享、Stalker Portal。

## 贡献

欢迎提交 Issue 与 Pull Request。提交代码前请确保 `./gradlew assembleDebug` 通过。

## 许可证

[Apache License 2.0](LICENSE)
