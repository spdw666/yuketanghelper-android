# 构建与签名

## 工具版本

| 工具 | 版本 |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper | 8.11.1 |
| Android Gradle Plugin | 8.9.2 |
| Kotlin | 2.1.20 |
| Compile / Target SDK | 35 |
| Build Tools | 35.0.0 |
| Min SDK | 26（Android 8.0） |

Android Studio 打开仓库根目录，选择 JDK 17 并用 SDK Manager 安装上述组件。命令行环境设置 `JAVA_HOME`、`ANDROID_HOME`，确保 SDK 许可已经接受；或者只在本机 `local.properties` 写入 `sdk.dir`。此文件不提交到 Git。

## 执行检查

Windows PowerShell：

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

macOS / Linux：

```bash
bash ./gradlew :core:test :app:assembleDebug :app:lintDebug
```

单独运行核心测试：`bash ./gradlew :core:test`。

主要输出：

- APK：`app/build/outputs/apk/debug/app-debug.apk`
- 核心测试报告：`core/build/reports/tests/test/index.html`
- Lint 报告：`app/build/reports/lint-results-debug.html`

首次运行需联网下载 Gradle 与依赖。只有已有完整缓存时才使用 `--offline`。Wrapper 的分发地址和校验值在 `gradle/wrapper/gradle-wrapper.properties`；依赖仓库在 `settings.gradle.kts`。

## 调试签名与覆盖安装

默认调试构建由 Android Gradle Plugin 使用本机调试密钥签名。仓库不包含原交付 APK 的私钥，因此其他机器和 Actions 构建不能保证覆盖该版本。

若维护者本机已有需要复用的兼容调试签名文件，可通过已有构建属性指定路径：

```powershell
.\gradlew.bat :app:assembleDebug '-PprototypeDebugKeystore=D:/private/android-debug.jks'
```

此属性只覆盖默认 debug 配置的文件路径；别名和口令仍使用 Android 默认 debug 配置，不是通用 release 签名配置。私钥应保留在仓库外，避免把口令放进命令历史或提交文件。正式发行需维护者另行配置并保管固定 release 签名；当前项目未配置正式发行签名。

## GitHub Actions

工作流配置 JDK 17、SDK 35 和 Build Tools 35.0.0，执行同一组检查，上传调试 APK、JUnit XML 和 Lint 报告。下载路径为仓库 **Actions → 对应运行 → Artifacts**。

工作流无需真实课堂账号和 AI Key。CI 输出是临时调试包，不应当作稳定签名的正式更新渠道。本次整理只做本地构建和工作流静态检查，未在 GitHub 托管环境执行工作流。
