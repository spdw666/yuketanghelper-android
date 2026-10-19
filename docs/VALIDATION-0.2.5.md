# 雨课助手 Android 0.2.5 修复与验证

日期：2026-10-19。versionCode 7，包名 `cn.yukehelper.android.prototype`，Android 8.0+，Target SDK 35。

## 修复的问题

用户反馈：仍能收到课堂消息，但最近动态不断出现“课堂连接暂不可用，正在重连”。上游参考实现记录了课堂仍在进行时周期性断开连接的情况；原 Kotlin 实现在 hello 后清空重试次数，导致下次断开再次触发首条错误提示。仅凭截图不能确定手机上的每次断开原因。

修改前已通过两项新增回归测试复现：周期性轮换且持续收到题目时误报；连续连接后始终没有 hello 却缺少告警。两项测试在原实现上失败，修复后通过。

## 最终行为

- 正常稳定连接的轮换在 1 秒后静默重连。
- 连续 3 次未能稳定连接，或重试期间中断达到 15 秒，显示持续中断提示。同一轮故障每 2 分钟最多告警一次，稳定连接 10 秒后提示一次恢复。
- 短暂 hello 后立即断线不会清空退避；失败重试间隔为 1、2、4、8、16、30 秒，上限 30 秒。
- 15 秒没有收到课堂 hello 时取消该连接并重试。连续失败会刷新课堂凭据，重连保留同一堂课的自动操作实例及其去重状态。
- WebSocket 拒绝课堂凭据时重新获取，不直接判定账号登录失效；普通账号接口确认登录失效时仍停止该账号并要求登录。
- 错误只展示固定分类和数值状态码，不显示响应原文、Cookie、Token、Bearer 或任意异常消息。
- 进课堂提示改为“已进入课堂；考勤状态请以雨课堂为准”，动态二维码签到仍未实现。历史消息保留。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| 核心测试 | 88 项通过，0 失败、0 错误、0 跳过 |
| 新增连接测试 | 10 项：正常轮换、无 hello、持续故障限频、稳定恢复、短连接退避、凭据刷新、握手超时清理、超时取消、403、503 |
| Android 调试构建 | 成功 |
| Android Lint | 0 错误、0 警告 |
| APK 签名 | apksigner 验证通过，v2 有效；与原交付 0.2.4 证书一致 |
| APK 元数据 | 0.2.5 / versionCode 7 / min API 26 / target API 35 |
| APK 完整性 | ZIP 检查通过，无 ABI 专属原生库 |
| 源码 ZIP | 完整性、文件清单与文档内部文件链接检查通过 |
| 公开文件 | 排除签名、账号数据、APK、缓存、SDK 和本机配置；常见密钥和本机路径扫描通过 |

| 测试组 | 数量 | 失败 |
| --- | --- | --- |
| ActionApiTest | 5 | 0 |
| AiClientTest | 5 | 0 |
| AutomationTest | 21 | 0 |
| ConfigurationTest | 6 | 0 |
| CourseSelectionTest | 5 | 0 |
| DeepSeekTest | 9 | 0 |
| GlobalCourseSettingsTest | 8 | 0 |
| MonitorEngineTest | 11 | 0 |
| ProtocolTest | 6 | 0 |
| YuketangApiTest | 12 | 0 |

构建使用 JDK 17、Gradle 8.11.1、SDK 35 与已有缓存，执行 `:core:test :app:assembleDebug :app:lintDebug --offline`。网络测试使用本地 MockWebServer，时间相关测试使用可控时钟；没有访问真实课堂或收费 AI。

源码只修改 7 个实现、测试或构建文件：`MonitorEngine.kt`、`Models.kt`、`YuketangApi.kt`、两组对应测试、`MainActivity.kt` 和 `app/build.gradle.kts`。没有改动账号存储格式、课程设置或新增运行依赖。

尚未在手机、模拟器或 GitHub Actions 云端运行。签名一致和版本递增满足 Android 覆盖安装的基本要求，实际安装及数据保留仍需手机确认。建议先停止监测，覆盖安装后重新开始监测，验证课堂消息、锁屏、断网及恢复。无需卸载旧版。

原交付 APK SHA-256：`f60434e5d501bedc4f9637aa2ae1e311b20a659959b94be98298b751006676e8`

签名证书 SHA-256：`fc09a0329ad82a27175761a136029c4d70cba156ced3facaafa12141ae6eeef4`

原交付 APK 的签名不随源码分发；自行构建或 Actions 生成的调试包可能无法覆盖原安装。源码包不含私钥。首次 0.2.4 的历史记录见 [旧版验证](VALIDATION.md)和 [仓库整理记录](GITHUB_PACKAGE_VALIDATION.md)。
