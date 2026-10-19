**雨课助手 Android 0.2.4 验证记录**

日期：2026-10-16。本版目标：扩展 Android 系统版本、品牌和屏幕布局兼容性。

| 检查 | 结果 |
| --- | --- |
| Kotlin / Android App 编译 | 通过 |
| 已有核心回归测试 | 78 项通过，0 失败，0 错误，0 跳过 |
| Android Lint（最低 API 26） | 0 错误，0 警告 |
| APK 签名 | apksigner 通过，APK Signature Scheme v2 有效 |
| 与 0.1–0.2.3 的签名比较 | 证书 SHA-256 一致，可覆盖升级 |
| 包名与版本 | cn.yukehelper.android.prototype · 0.2.4 / versionCode 6 |
| 最低 Android / Target SDK | Android 8.0（API 26）/ API 35 |
| APK ZIP 完整性 | 通过 |
| APK 大小 | 2,466,338 字节 |
| CPU 相关原生 .so 库 | 无；无需选择 ARM / ARM64 / x86 分包 |
| 新增运行依赖 / 存储权限 | 无 |

| 测试组 | 用例数 | 失败 |
| --- | --- | --- |
| ActionApiTest | 5 | 0 |
| AiClientTest | 5 | 0 |
| AutomationTest | 21 | 0 |
| ConfigurationTest | 6 | 0 |
| CourseSelectionTest | 5 | 0 |
| DeepSeekTest | 9 | 0 |
| GlobalCourseSettingsTest | 8 | 0 |
| MonitorEngineTest | 5 | 0 |
| ProtocolTest | 6 | 0 |
| YuketangApiTest | 8 | 0 |

**兼容代码检查**

- Android 8/9 前台服务调用不传新版服务类型；Android 10+ 使用 dataSync 类型，Android 12+ 请求立即显示前台通知，Android 13+ 分支申请通知权限。保留最长 4 小时、系统 onTimeout 和取消处理。
- Android 8/9 保存二维码使用 ACTION_CREATE_DOCUMENT；Android 10+ 才访问 RELATIVE_PATH / IS_PENDING 相册 API。没有增加读写外部存储权限。
- 屏幕开孔 API 隔离在 Android 9+，系统栏/IME 类型化 Insets 隔离在 Android 11+；Android 8 使用旧版系统窗口 Insets。横屏左右导航栏、安全区域和键盘均纳入布局。
- 内容容器随窗口重新测量并限制 840dp 最大宽度；主要按钮可换行并随字体展开。二维码按可用宽度缩放，课程搜索/全选控件随列表滚动，短窗口收起重复提示。
- 所有页面允许调整窗口大小；网页组件创建失败或渲染进程退出提供扫码入口；TTS 和唤醒锁异常降级。
- 后台适配页根据设备品牌显示提示，使用标准系统设置 Intent；不提供的入口依次回退到应用详情、系统设置。通知授权返回后刷新状态。
- Android 10+ 禁止系统强制深色转换，保留已有配色。没有引入 Google Play 服务依赖。

本版没有修改课堂协议、AI、课程规则和配置格式；已有测试继续覆盖 DeepSeek 请求、答案校验、取消、回退、课程全选/全不选、通用规则与旧版迁移、WebSocket、提交去重、手动答案保护和多账号隔离。课程页保持只供选择，通用设置保持独立页面。

**尚未执行的设备验证**

本版未在真实手机或 Android 模拟器运行。Lint 能检查低版本 API 误用，无法证明不同 ROM 的界面、后台保活、WebView 或存储提供方行为。vivo X300、其他品牌、Android 8/9、平板和折叠屏均需设备验证。未测量实际帧率、耗电或后台持续时长。

建议设备验证矩阵：

| 场景 | 检查内容 |
| --- | --- |
| Android 8/9 | 新安装、登录、系统文件选择器保存二维码、前台通知 |
| Android 10–12 | 相册保存、横竖屏、三键/手势导航、品牌电池设置 |
| Android 13–15 | 通知允许/拒绝、通知类别、键盘遮挡、系统停止服务 |
| 320dp 窄屏、字体 1.3–2 倍 | 登录和设置按钮可达、选课滚动、应用选择按钮 |
| 横屏 / 分屏 / 平板 / 折叠屏 | 左右系统栏、窗口缩放、内容限宽、草稿恢复 |
| vivo / 小米 / OPPO / 一加 / 三星 / 华为 / 荣耀等 | 设置入口回退、锁屏与网络切换、系统清理后重新开启 |
| 覆盖旧版 | 登录、DeepSeek Key、课程勾选、通用规则保留 |

纯 HarmonyOS NEXT 不支持 Android APK。更新的安卓版本未作验证承诺。品牌后台限制不能由 App 绕过；需按手机提供的选项设置并实际验证。测试没有访问真实课堂或真实 AI，也未消耗用户 API 余额。

APK SHA-256：`ed42258a1aeb263a90dd74f68a0fb2628ceb720b555f6553d375087f370f4638`

签名证书 SHA-256：`fc09a0329ad82a27175761a136029c4d70cba156ced3facaafa12141ae6eeef4`
