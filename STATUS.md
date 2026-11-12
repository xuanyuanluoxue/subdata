# subdata · 电脑端工程状态

> 最初由手机端 Lead 于 2026-11-13 00:52 写下的交接占位；**现已由执行子 Agent 于 00:58 完成**，
> 进度表已按实际结果更新。权威文档：`README.md`（怎么用）、`PLAN.md`（调研/设计/风险/TODO）。

## 一句话

在电脑上开发一个 Xposed/LSPosed 模块：让被注入的 App（学习通 com.chaoxing.mobile 7.0.4）
拥有多份独立数据目录，按 slot（a/b）切换，实现一台手机两个账号。

## 当前进度

| 阶段 | 状态 |
|---|---|
| 建目录 | ✅ `D:\code\android\subdata`（WSL `/mnt/d/code/android/subdata`），已 git init + 首次提交 |
| 环境探测 | ✅ JDK 21；Android SDK `~/Android/Sdk`（platform `android-37.0` + build-tools `37.0.0`）；Gradle 9.9.0 已在 `~/.gradle/wrapper/dists` 缓存；AGP 9.5.1 已缓存 |
| 开源调研 | ✅ 完成，结论见 `PLAN.md` 第一节（含 2026-11 活跃项目 `io.github.expirt.storageredirect`、`AcE77505/storage-redirect-module`；加载器用 `JingMatrix/Vector` v2.2） |
| 工程骨架 | ✅ Gradle 9.9 + AGP 9.5.1 + Java 17，传统 Xposed API（api-82，compileOnly，jar 已 vendor 到 `app/libs/`） |
| 编译通过 | ✅ `./gradlew clean assembleDebug` BUILD SUCCESSFUL；`testDebugUnitTest` 13 个测试全绿 |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk`（888 KB，com.xavier.subdata，compileSdk 37 / minSdk 26 / targetSdk 34） |

## 下一步（手机端接手）

1. 装 APK → LSPosed/Vector 启用模块 → 作用域勾 `com.chaoxing.mobile` → 强制停止该 App
2. 首次启动后看 `su -c 'cat /data/data/com.chaoxing.mobile/subdata/subdata.log'`，应为 `installed slot=a hooks=..`
3. 切成干净第二账号：
   `su -c 'echo clone_on_first_run=false >> /data/data/com.chaoxing.mobile/subdata/subdata.conf'`
   `su -c 'echo b > /data/data/com.chaoxing.mobile/subdata/slot; am force-stop com.chaoxing.mobile'`
4. 回滚：LSPosed 里取消作用域即可，原始数据从未被改动

## 禁止（仍然有效）

- 不要改系统分区、不要 hook system_server
- 不要执行删盘/格式化/重启
