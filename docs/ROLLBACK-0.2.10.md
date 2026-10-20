# 0.2.10 功能回退与验证

日期：2026-10-21。版本名 `0.2.10`，安装版本号 `14`。包名 `cn.yukehelper.android.prototype`，Android 8.0+，Target SDK 35。

## 恢复内容

当前源码按保存的 0.2.10 源码包恢复。全部 Kotlin 源码、核心测试、资源、Gradle 配置和 Wrapper 已逐文件对照原包；代码相关文件仅 `app/build.gradle.kts` 的 `versionCode` 从 12 改为 14，其余完全一致。移除 0.2.11 新增的 ModelScope 快捷按钮、引导及相关测试。原 0.2.10 已有的手动 ModelScope、DeepSeek、Gemini、全局 AI 配置、多账号和答题修复继续保留。

用户此前已确认原 0.2.10 在其课堂恢复自动答题。本回退构建没有访问真实课堂、调用真实 AI 或进行手机安装测试，不代表全部设备和课堂均已验证。

## 为什么安装版本号为 14

原 0.2.10 为安装版本号 12，0.2.11 为 13。Android 通常不允许使用系统安装器直接覆盖安装较低版本号。本回退包仅把安装版本号提高到 14，功能和显示版本恢复为 0.2.10。已核对包名、签名与版本号，可覆盖原交付 0.2.11 或 0.2.10；无需卸载，不清除本机账号、课程、通用规则或全局 AI 设置。配置格式继续为原有版本 4，修改前已经保存的密钥优先顺序和回退开关继续保留。

先停止监测，安装 `YuketangHelper-Android-0.2.10-rollback-debug.apk`，再重新开始监测。未来覆盖此包的版本应使用大于 14 的安装版本号。自行构建或其他来源 APK 的签名可能不同。

## 本地检查

| 检查 | 结果 |
| --- | --- |
| 原始 0.2.10 APK 和源码 ZIP | SHA-256 与历史交付记录一致，未覆盖 |
| 功能实现 | 与原 0.2.10 一致；仅安装版本号调整 |
| 核心测试 | 146 项通过，0 失败、0 错误、0 跳过 |
| Android 调试构建 | 成功 |
| Android Lint | 0 错误、0 警告 |
| 元数据 | 0.2.10 / versionCode 14 / min API 26 / target API 35 |
| APK 签名 | v2 有效，与原交付 0.2.10 / 0.2.11 相同 |
| 源码包 | 排除缓存、签名私钥、账号数据、APK 及本机路径；检查内部链接和 ZIP 完整性 |

| 测试组 | 数量 | 失败 |
| --- | --- | --- |
| ActionApiTest | 11 | 0 |
| AiClientTest | 5 | 0 |
| AutomationTest | 26 | 0 |
| ConfigurationTest | 6 | 0 |
| CourseSelectionTest | 5 | 0 |
| DeepSeekTest | 9 | 0 |
| GlobalAiSettingsTest | 9 | 0 |
| GlobalCourseSettingsTest | 8 | 0 |
| MonitorEngineTest | 22 | 0 |
| ProtocolTest | 10 | 0 |
| YuketangApiTest | 35 | 0 |

使用 JDK 17 / Gradle 8.11.1 / SDK 35，离线运行 `:core:test :app:assembleDebug :app:lintDebug`。测试使用模拟服务器和可控时钟，没有真实课堂提交或 AI 额度消费。恢复前源码以及被移出的 0.2.11 文件和构建产物已在本机工作目录备份，历史交付包继续保留。工作区仍在 D 盘。

原答题修复记录见 [0.2.10 验证](VALIDATION-0.2.10.md)。动态二维码考勤仍未支持。

APK SHA-256：`2085155af67fd6298bd2591bf38b67a1fe0bbecf78e27a0cc27a4c611f6b72e6`

签名证书 SHA-256：`fc09a0329ad82a27175761a136029c4d70cba156ced3facaafa12141ae6eeef4`
