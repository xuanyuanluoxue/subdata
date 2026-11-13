# 分工与决策记录

本文件记录 B 线二期（UI 优化 + root 功能测试）的分工、决策与被否决的方案。
环境：电脑（Windows + WSL），源码在 `/mnt/d/code/android/subdata`（= `D:\code\android\subdata`）。
**全程只在模拟器 `xiaoi-p0`（emulator-5554）上操作，真机（`100.124.42.34:5555`）一次都没碰。**

## 1. 谁做了什么

| 角色 | 由谁执行 | 做了什么 | 产出 |
|---|---|---|---|
| 主 Agent（编排、实现、验收） | DSH 子 Agent | 读完全部源码；定 UI 结构；写 8 个 Java 文件 + 6 个布局 + 主题/字符串；写测试脚本；跑构建与模拟器验证 | `app/src/main/**`、`tools/**`、本文件、`TEST-REPORT.md` |
| opencode `subdata-lead`（主 Agent 配置） | opencode v1.18.26 | 通过 `task` 工具把只读调研委派给 `researcher` 子 Agent，并追加决策记录 | `opencode.json`、`docs/RESEARCH-UI.md` |
| `researcher`（opencode 子 Agent） | opencode 子会话 | 只读扫描界面代码，盘点硬编码中文、确认抽词结论 | `docs/RESEARCH-UI.md` |
| 测试（root 层） | 主 Agent 在模拟器内 | `mount --bind` 双 slot 隔离、inode/属主/SELinux 校验、PathMap 扫描 | `tools/root-test.sh`、`tools/build-pathmap-cli.sh` |

opencode 侧的能力已实测：`opencode --version` = 1.18.26，配置 schema 里
`agent.<name>.mode` 支持 `primary | subagent | all`，主 Agent 通过内置 `task` 工具派发子 Agent。
配置见仓库根目录 `opencode.json`：1 个 primary（`subdata-lead`）+ 4 个 subagent
（`researcher` / `ui-dev` / `test-eng` / `reviewer`），各自带 `prompt` 与 `permission`。

```bash
# 主 Agent 自己带子 Agent 跑（默认模型 qianfan/glm-5.3 不存在，必须显式 -m）
opencode run -m deepseek/deepseek-v4-pro --agent subdata-lead "按调研 → 分工 → 决策 → 开发 → 测试推进"
```

## 2. 关键决策

### D1 界面用「4 个独立 Activity」而不是 ViewPager2 + Fragment
- **选它**：单 Activity 承载 6 个功能块会让 `MainActivity` 变成 800 行；而 ViewPager2 + Fragment 需要
  Fragment 生命周期、状态恢复、`ViewModel` 一整套，对这个体量的模块属于过度设计。
- 结果：`MainActivity`（首页 / 状态卡 / 开关 / 危险区）+ `AppPickerActivity`（白名单）+
  `SlotActivity`（slot 管理）+ `LogActivity`（日志），每个 150–260 行，跳转用 Intent。
- 代价：没有横向滑动切页。对「配置类」界面可以接受。

### D2 目标 App 的私有目录一律走 `su`，不在模块进程里假装能直读
- 模块自己的 APK 不是目标 App，`/data/data/com.chaoxing.mobile` 按 SELinux 规则读不到。
- 所以 `RootShell` 统一用 `ProcessBuilder("su","-c",...)` + **超时**（Magisk 弹授权时不会卡死界面），
  参数一律 `shq()` 单引号转义；写文件走 `base64 -d`（彻底避开引号/换行/`$` 展开）。
- **写回后必须 `chown` 到目标 uid + `chmod` + `restorecon`**：否则 root 写出来的文件属主是 root、
  SELinux 标签是 shell 的，被注入的 App 读自己的 `subdata/slot` 会被拒——这是隐藏的坑，
  `RootShell.handOverToApp()` 专门处理。
- 拿不到 root 时界面显示「未获得 root 授权」，**不降级成假数据**。

### D3 状态卡读取 `subdata.log` 的最后一条 `installed slot=… hooks=…`
- 不新增任何跨进程通信（不写 ContentProvider、不用 socket）。模块本来就在写这行日志，
  界面只是读它，风险最低。
- 状态判定顺序：没 root → 目标没装 → 没有 `installed` 记录（未生效）→ `disabled by`（被配置停用）
  → 有 `installed`（已生效）。

### D4 只依赖 `com.google.android.material:material:1.12.0`
- 它已经带 appcompat / recyclerview / fragment，没有额外引 lifecycle-viewmodel 之类。
- 主题 `Theme.Material3.DayNight.NoActionBar`，浅/深色各自一套 `colors.xml`（`values-night`），
  有系统暗色就自动跟随，不写手动切换逻辑。

### D5 root 测试用「系统 App + `mount --bind`」，不用学习通
- 选 `com.android.deskclock`（模拟器自带、有真实数据目录、崩溃也不影响别人）。
- `mount --bind` 能真实模拟「切换数据目录」，比只测 `PathMap` 字符串映射更接近真机行为。
- 全程 `trap cleanup EXIT` 兜底 umount，结束前删除测试目录；只动 `/data/local/tmp/subdata-test`。

## 3. 被否决的方案

| 方案 | 否决原因 |
|---|---|
| 界面直接读目标 App 的 `subdata.log`（无 root） | SELinux 下必然 `EACCES`，只能显示假状态 |
| 用 `androidx.preference` 做设置页 | 依赖更重，且状态卡/危险区仍要自绘，收益低 |
| ViewPager2 + Fragment 做 6 个 tab | 过度设计，Fragment 状态恢复会引入新 bug |
| 用 `File` 直写 conf（root 下） | 引号/换行/`$` 展开不可控，改用 `base64 -d` |
| 用 bind mount 记录「当前 slot」（而不是写 slot 文件） | 挂载不持久、重启失效，且要改系统状态；写一行文件更简单可回滚 |
| 在模拟器里装 Xposed/Magisk 验证注入 | 时间预算内装 Zygisk Next + LSPosed 风险高、收益低，本轮明确记为「未验证」 |
| 在真机上验证 | 用户红线，禁止 |

## 4. 遗留 / 下一步
- Xposed 注入层未验证（模拟器无 LSPosed），需在真机或可装 LSPosed 的环境补验。
- `AppPickerActivity` 的全量扫描用主线程外的单线程 + 一次性图标加载，App 数量极大时可考虑分页。
