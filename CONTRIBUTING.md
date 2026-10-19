# 贡献说明

先阅读 [构建文档](docs/BUILDING.md)、[已知限制](docs/KNOWN_ISSUES.md)和 [许可证状态](LICENSE_STATUS.md)。项目尚未指定自有源码许可证；请在确认贡献内容的授权条件后再提交贡献，本说明不代替许可证。

修改 `core/` 时，为实际行为变化补充必要的模拟网络或可控时钟测试。避免在自动测试中访问真实课堂、提交真实答案或消耗 AI 余额。保留取消处理、多账号隔离及已有手动答案保护。

修改 Android 页面时，检查 Android 8 起的 API 隔离、大字体、横屏、分屏与系统栏避让。无法进行实机验证时，请在 Pull Request 中说明。

提交前执行：

```bash
bash ./gradlew :core:test :app:assembleDebug :app:lintDebug
```

PR 描述应说明问题、最终行为和实际验证结果。Issue、截图、测试数据和日志中请使用虚构账号及密钥占位符；不要提交签名私钥、Cookie、登录二维码或包含个人信息的课堂记录。
