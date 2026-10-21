# 雨课助手 Android

> **0.2.12 作业提醒（2026-10-21）**：在 0.2.10 界面调整版上增加“消息 → 作业提醒”，同步已选课程的作业标题、可读取的要求和截止时间。监测期间每轮结束后间隔 5 分钟检查，也可手动同步；新作业和要求、截止时间变更可通过本机通知提醒。安装版本号为 16，沿用此前签名。详见 [作业提醒说明](docs/HOMEWORK.md)。

Kotlin 原生 Android 雨课堂客户端原型，提供多账号课堂监测、课程选择、通用课程规则、本机通知、中文语音，以及 DeepSeek、ModelScope 和 Gemini 接入。

**当前版本：0.2.13 · Android 8.0+ · JDK 17 · Compile/Target SDK 35**

项目处于原型阶段，非雨课堂官方客户端，与雨课堂、清华大学或学堂在线无隶属关系。保留 0.2.10 的答题修复、全局 AI 设置和精简后的添加密钥入口。本次新增作业的只读同步、按账号保存的缓存和提醒去重；接口依据公开网页脚本核实，用户于 2026-10-21 反馈本次作业功能验证成功；未提供逐项测试记录，不同机型、站点、通知与覆盖安装情况仍需继续确认。详见 [作业功能与来源](docs/HOMEWORK.md)。

> **0.2.13 动态二维码签到（2026-10-21）**：新增「扫码 / 相册识别 / 粘贴内容 → 雨课堂两步接口签到」。二维码内容只在本机解码，随后 `POST /api/v3/app/scan` 换取课堂编号，再用 `POST /api/v3/lesson/checkin`（`source:21` + `joinIfNotIn`）提交；只有 `code==0` 才显示成功。动态码由教师端按当场所签发、约 8 秒刷新，本 App 不生成也不伪造，无法替代现场取码；进课堂仍不等于完成动态考勤。真实课堂回执尚未验证。详见 [协议来源与边界](docs/DYNAMIC_QR_ATTENDANCE.md)。

课堂编号按接口返回的数字或字符串类型发送；数字编号遇到 HTTP 400 / 50400 且无明确其他校验要求时，以网页使用的字符串形式额外尝试一次，保持账号、站点、编号值及 source 不变。入课拒绝会立即提示；HTTP 400 和入课业务错误每 60 秒重试，同一轮故障每 2 分钟最多提醒一次。错误响应限量读取，只保留数值代码、编号类型及固定校验分类，不显示响应原文或凭据。普通消息连接继续在收到有效题目、点名、弹幕或课堂状态后确认恢复；安静的课堂保持 10 秒后确认恢复。详见 [入课错误说明](docs/LESSON_ENTRY_ERRORS.md)和 [使用文档](docs/USER_GUIDE.md)。

## 功能

| 功能 | 当前实现 |
| --- | --- |
| 登录与账号 | 微信扫码、网页登录；多账号隔离登录会话、课程选择和消息 |
| 课程选择 | 紧凑索引、搜索、全选、全不选、选择草稿 |
| 通用规则 | 独立设置页；所有账号、所有课程使用同一套课程规则 |
| 课堂监测 | HTTP 轮询、WebSocket、重连、课堂消息记录 |
| 作业提醒 | 消息页作业列表、要求与截止时间、手动和监测期间定期同步、本机通知及跨重启去重 | 
| 动态签到 | 相机扫码、相册识别、粘贴二维码内容；`app/scan` + `lesson/checkin` 两步提交，固定分类错误提示 | 
| AI 与操作 | DeepSeek、ModelScope 千问、Gemini；所有账号共用 AI 配置；可配置的答题、弹幕和红包操作 |
| 本机提醒 | 通知、中文 TTS；按事件类型配置 |
| 机型适配 | 屏幕安全区域、键盘避让、大字体、横屏、分屏、大屏限宽及品牌后台设置提示 |
| 演示模式 | 使用本地模拟课堂，不调用真实课堂或 AI 服务 |

自动答题、弹幕和红包默认关闭。每次后台监测最长 4 小时。品牌后台限制和不同系统行为仍需设备验证；纯 HarmonyOS NEXT 无法安装本 APK。使用时请遵守课堂及平台规则。

## 安装与使用

仓库维护者可在 GitHub **Releases** 发布 APK；也可以使用 Actions 生成的调试安装包或自行构建。源码目录不包含 APK。

1. 安装 APK 并登录账号，也可先体验演示模式。
2. 在“课程”页搜索、勾选课程，点击“应用课程选择”。
3. 到“设置 → 通用课程设置”保存统一规则；在“AI 服务与密钥（全局）”配置一次，所有账号共用。
4. 在“设置 → 后台运行与机型适配”检查通知和电池设置，回总览开始监测。
5. 在“消息 → 作业提醒”点击“立即同步作业”，查看要求及截止时间；开启监测后会定期检查。作业系统通知可在“通用课程设置 → 本机通知 → 作业”控制。

已有安装请使用相同签名的 APK 覆盖更新。自行构建及 GitHub Actions 生成的 APK 通常使用不同的调试签名，无法保证覆盖维护者发布的版本。不要为解决签名冲突直接卸载，卸载会删除本机账号和配置。

## 本地构建

Android Studio 打开仓库根目录，安装 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。命令行构建需设置 `ANDROID_HOME`（或在本机 `local.properties` 中设置 `sdk.dir`），并接受 Android SDK 许可。

Windows PowerShell：

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

macOS / Linux：

```bash
bash ./gradlew :core:test :app:assembleDebug :app:lintDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。首次构建需要联网下载 Gradle 和依赖。Gradle Wrapper 校验分发包 SHA-256；仓库配置保留国内 Maven 镜像。完整步骤见 [构建文档](docs/BUILDING.md)。

## GitHub Actions

[Android CI](.github/workflows/android.yml) 在推送 `main`、提交 Pull Request 或手动触发时运行核心测试、调试构建及 Lint，并上传调试 APK 和报告。无需配置真实账号或 API Key。工作流不自动发布 Release。

## 项目结构

```text
app/                    Android 页面、后台服务、通知、加密存储
core/                   网络、协议、配置、课堂监测、AI 与核心测试
gradle/wrapper/         Gradle Wrapper 与校验配置
.github/                CI、Issue 与 Pull Request 模板
docs/                   使用、构建、验证、发布与动态签到说明
THIRD_PARTY_NOTICES.md   来源及依赖说明
LICENSE_STATUS.md       许可证状态
```

## 文档

- [使用与升级](docs/USER_GUIDE.md)
- [构建、签名与 CI](docs/BUILDING.md)
- [作业提醒与接口来源](docs/HOMEWORK.md)
- [0.2.12 验证记录](docs/VALIDATION-0.2.12.md)、[0.2.10 历史记录](docs/VALIDATION-0.2.10.md)及 [0.2.9 历史记录](docs/VALIDATION-0.2.9.md)
- [0.2.4 历史验证记录](docs/VALIDATION.md)及 [首次源码包整理记录](docs/GITHUB_PACKAGE_VALIDATION.md)
- [动态二维码签到核实](docs/DYNAMIC_QR_ATTENDANCE.md)
- [已知限制](docs/KNOWN_ISSUES.md)
- [发布步骤](docs/RELEASING.md)及 [0.2.12 Release 文案](docs/releases/v0.2.12.md)
- [变更记录](CHANGELOG.md)及 [贡献说明](CONTRIBUTING.md)

## 来源与许可证

接口行为分析参考 [Yuketang-Helper-Web v7.1.0](https://github.com/dvdsanyi/Yuketang-Helper-Web/tree/v7.1.0)。本项目为 Kotlin 实现，仓库不附带上游桌面程序、Python 源码或网页资源。

项目自有源码的许可证尚未指定，未将仓库声明为 MIT、Apache-2.0 或 GPL。依赖及 Gradle Wrapper 保持各自许可证，详见 [许可证状态](LICENSE_STATUS.md)与 [来源和依赖](THIRD_PARTY_NOTICES.md)。公开可读不等于已授予开源许可。
