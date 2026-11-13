# SubData UI 文案调研（researcher 只读结论）

调研范围：`app/src/main`（全部 12 个 Java + 7 个 layout XML + strings.xml）。已排除非 UI 字面量：XposedBridge.log / logFile / RootShell 的 shell 命令与路径 / prefs·conf key / 包名·slot 名等数据，以及 PathMap 的 CLI System.out 与异常消息。

## 一、已抽进 strings.xml（良好）

- 共 **99 个 `<string>` key** + **1 个 `<string-array>`**（xposed_scope，值为 com.chaoxing.mobile）。
- 4 个 Activity（Main / AppPicker / Slot / Log）的标题、按钮、对话框、Snackbar、状态文案全部走 `R.string.*`，无 setText/Toast/Dialog 硬编码。
- 7 个 layout XML 里所有 `android:text` 均为 `@string/...` 引用；Manifest 的 label 与 meta-data（xposed_description）也全引用。**布局零硬编码。**
- key 分类：通用(beta/beta_hint/refresh/cancel/save/ok/retry)、首页状态(status_*/section_*/label_*/btn_*/switch_*/danger_*/about_*/version_with_beta/slot_summary_with_current)、白名单(title_app_picker/picker_*)、Slot(slot_*/title_slots)、日志(log_*/title_log)、root(root_denied/no_target_selected/pkg_label)。

## 二、仍硬编码在 Java/XML 里的用户可见字面量

| 文件:行 | 字面量 | 场景 |
|---|---|---|
| MainActivity.java:117 | `"?"` | showVersion() 中 versionName 取不到时的兜底，最终显示为「? · Beta」 |

- 全仓库唯一一处「会直接出现在界面上的硬编码字符串」。建议抽成 key（如 `version_unknown`=「?」）或并入 version_with_beta。
- 布局 XML：零硬编码。

## 三、排除项（非 UI 文案，不计入清单）

- LogActivity.java:86 `ClipData.newPlainText("subdata.log", …)` —— 剪贴板标签，非界面文案（可选抽 key）。
- PathMap.java:34 `IllegalArgumentException("slot must not be empty")` —— 异常消息，属 CLI/测试工具。
- 其余字符串均为 shell 命令、文件路径、prefs/config key、Xposed/文件日志。

---

## 决策记录（主 Agent）

**结论：4 个独立 Activity（Main / AppPicker / Slot / Log），不用 ViewPager2+Fragment。**

理由：
- 页面之间不是「可滑动切换的同级 Tab」，而是「首页 → 下钻/弹层式」流程（白名单、Slot、日志都从 Main 进入，各自带 toolbar 返回键 `setNavigationOnClickListener(v -> finish())`），独立 Activity 天然匹配各自的返回栈与生命周期。
- 每个页面的状态相互独立（目标列表 / slot 列表 / 日志），无需共享宿主 Activity 的状态与 FragmentManager 的 backstack 保存，崩溃面更小。
- Xposed 模块 UI 规模小，4 个轻量 Activity + 各自的 layout 最直观，无需引入 fragment 生命周期、adapter、tab 选择态等额外复杂度。

被否决的方案：
- **ViewPager2 + Fragment**：页面非滑动同级、不需要左右手势，反而引入 fragment 生命周期与状态管理，纯属多余。
- **单 Activity + Navigation Component / NavHost**：需引入 navigation 依赖与 nav graph，对 4 页的小界面是过度设计。
- **底部 Tab 导航 / NavigationBar**：首页与子页面是层级关系而非平行 Tab，交互模型不符。
- **Compose / WebView**：仓库无 Compose 依赖，模块本身定位原生 View，不做技术栈切换。