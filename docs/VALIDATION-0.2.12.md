# 0.2.12 验证记录

日期：2026-10-21。版本名 0.2.12，versionCode 16，Android 8.0+，Target SDK 35。

## 结果

| 检查 | 结果 |
| --- | --- |
| 核心测试 | 167 项，0 失败、0 错误、0 跳过 |
| Android 调试构建 | 成功 |
| Android Lint | 0 问题 |
| APK 元数据 | 包名 cn.yukehelper.android.prototype，0.2.12 / 16 / min API 26 / target API 35 |
| APK 签名 | v2 验证有效，与原交付 0.2.10 和界面调整包相同 |
| 旧版功能 | AI、课堂监测协调、题目自动操作、提交协议、课程选择核心逐文件与 0.2.10 界面调整包一致；独立 DeepSeek 添加按钮仍已移除 |
| 配置迁移 | 旧通知总开关、原有通知类型及语音设置保留；加入作业通知类型；新版本取消后的选择不再被重加 |
| SQLite SQL 迁移 | 用实际 SQL 在本机 SQLite 验证 v2 到 v3：旧消息保留、作业键关联、相同作业编号的账号隔离、更新及移除账号数据隔离 |
| 源码打包 | 排除缓存、SDK、本机路径、账号数据和签名私钥；检查 Markdown 内部链接、ZIP 完整性及逐文件内容 |

构建命令：`:core:test :app:assembleDebug :app:lintDebug --offline`，JDK 17 / Gradle 8.11.1 / SDK 35。

作业接口测试使用本地模拟服务器，核对 GET 方法、官方路径、分页参数、账号 Cookie 与资源编号；未调用开始作业或提交答案接口。新增加 21 项核心测试，覆盖解析、时间换算、文本提取、去重、撤回隐藏、分页上限、失败保留、取消和迁移。

## 用户验证反馈

2026-10-21，用户针对本次作业功能反馈“验证成功”。未提供逐项测试过程，因此该反馈不代表所有设备、学校站点、系统通知、跳转及覆盖安装均已验证。

## 开发环境验证范围与待确认项

开发环境使用本地模拟接口，未调用真实账号作业同步、真实 AI 或提交课堂答案，也未在手机或模拟器安装本包。上述自动检查与用户反馈分别记录。Android SQLiteOpenHelper 生命周期、实际通知、跳转及不同站点仍需专项实机确认。SQL 检查使用本机 SQLite，不能代替 Android 数据库运行测试。公开脚本核实不是正式接口文档，也不能证明全部学校、课程类型均兼容。

自动同步仍依赖监测开启，每轮结束后间隔 5 分钟，每次最多 4 小时。只检查每门课程最近 200 条动态；详情和超长内容的限制见 [作业说明](HOMEWORK.md)。没有额外实现截止前闹钟。

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
| HomeworkApiTest | 5 | 0 |
| HomeworkTest | 16 | 0 |
| MonitorEngineTest | 22 | 0 |
| ProtocolTest | 10 | 0 |
| YuketangApiTest | 35 | 0 |

APK SHA-256：`9d5e85050efea961016650f697300b7547d383381220df1bd692aeaa98195b4e`

签名证书 SHA-256：`fc09a0329ad82a27175761a136029c4d70cba156ced3facaafa12141ae6eeef4`
