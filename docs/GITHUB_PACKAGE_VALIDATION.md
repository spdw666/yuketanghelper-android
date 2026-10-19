# GitHub 源码包验证记录

> 此页保留首次整理的 0.2.4 历史结果。当前 0.2.6 的改动及交付验证见 [0.2.6 验证记录](VALIDATION-0.2.6.md)。

日期：2026-10-19。验证对象为本次独立整理的 GitHub 仓库目录，应用版本保持 0.2.4。

| 检查 | 结果 |
| --- | --- |
| 实现、资源及构建文件 | 47 个文件与原 0.2.4 项目逐字节一致 |
| 核心单元测试 | 78 项通过，0 失败、0 错误、0 跳过 |
| Android 调试构建 | `:app:assembleDebug` 成功 |
| Android Lint | 0 错误、0 警告 |
| Markdown 内部文件链接 | 目标文件均存在 |
| Gradle Wrapper JAR | ZIP 完整性检查通过 |
| Linux 构建入口 | `gradlew` 使用 LF 换行；CI 通过 `bash` 调用 |
| GitHub 配置 | 工作流与 Issue 模板已静态检查；未在 GitHub 执行 |
| 公开文件扫描 | 未发现常见私钥、服务商密钥、GitHub Token、JWT 或本机用户目录路径 |
| 源码 ZIP | 完整性及目录清单校验通过；包含隐藏的 `.github` 和 Git 配置文件 |

本地使用 JDK 17、Gradle 8.11.1、Android SDK 35 及已有依赖缓存，执行 `:core:test :app:assembleDebug :app:lintDebug --offline`。验证使用本机已有的 Gradle 安装，并非一次全新环境下的 Wrapper 下载测试。测试采用模拟服务，未登录真实课堂或调用付费 AI。

交付目录和 ZIP 排除了 SDK、构建产物、Gradle 缓存、`local.properties`、签名私钥、账号数据和 APK。本机生成的验证报告保留在交付目录之外。

本次只整理仓库和文档，没有修改应用行为、创建远程仓库或推送。未进行本版本的手机、模拟器、覆盖安装及 GitHub Actions 云端验证。动态二维码签到仍未支持；设备和接口限制见 [已知限制](KNOWN_ISSUES.md)，原有 APK 的历史验证见 [0.2.4 验证记录](VALIDATION.md)。
