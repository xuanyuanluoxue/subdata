# subdata · 电脑端工程状态

> 由手机端 Lead 于 2026-11-13 00:52 初始化（原派单子 Agent 未见产出，目录为空）。
> 接手人请先读本文件，再读 PLAN.md。

## 一句话

在电脑上开发一个 Xposed/LSPosed 模块：让被注入的 App（学习通 com.chaoxing.mobile 7.0.4）
拥有多份独立数据目录，按 slot（a/b）切换，实现一台手机两个账号。

## 当前进度

| 阶段 | 状态 |
|---|---|
| 建目录 | ✅ `D:\code\android\subdata`（WSL `/mnt/d/code/android/subdata`） |
| 环境探测 | ✅ JDK 21 (`/usr/bin/javac`)；Android SDK `~/Android/Sdk` 与 `C:\Users\Chatxavier\AppData\Local\Android\Sdk` 均在；**PATH 里没有 gradle / sdkmanager**，需要找 SDK 里的 gradle wrapper 或 Android Studio 自带 gradle |
| 开源调研 | ⬜ 未完成（PLAN.md 里已给出候选清单与初步判断，需核实） |
| 工程骨架 | ⬜ 未开始 |
| 编译通过 | ⬜ 未开始 |

## 下一步（按顺序）

1. 读 PLAN.md，确认技术路线（推荐：自研最小实现，参考 Storage Redirect 思路）
2. 探测/补齐构建环境：`ls ~/Android/Sdk/{platforms,build-tools}`；找 `gradle` 可执行文件；没有就用 `gradle-wrapper` + npmmirror/腾讯镜像下载
3. 搭 Gradle 工程（Kotlin，LSPosed API），先做一个「hook Context.getFilesDir 返回 slot 路径」的最小版本
4. `./gradlew assembleDebug` 通过，APK 落在 `app/build/outputs/apk/debug/`
5. 写 README（安装到 LSPosed、范围勾选、切 slot、回滚）

## 禁止

- 不要连手机、不要装 APK 到手机（手机端由 Lead 做）
- 不要改系统分区、不要 hook system_server
- 不要执行删盘/格式化/重启
