# 截图目录

本轮的界面截图**没有拿到**，原因在模拟器侧，不在 App：

`adb exec-out screencap -p`（guest 内 `screencap` 同样）会挂在宿主的 gralloc 回读：

```
Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma
  #03 mapper.ranchu.so (GoldfishMapper::readFromHost)
```

这是 `aosp_atd` + `-gpu swiftshader_indirect` 的 gfxstream 缺陷；同一时刻
`uiautomator dump`、`dumpsys activity`、`logcat` 全部正常，App 零崩溃。

已尝试 `-gpu off` 重启该 AVD：120 秒内未 boot 完成（`sys.boot_completed` 为空），
预算内没有继续折腾，随后已把模拟器恢复回 `swiftshader_indirect`。

**替代证据**：`ui-hierarchy.xml` 是界面的完整视图树（uiautomator dump），
可核对每个区块的文案、可见性、控件类型，等价验证了布局与字符串绑定正确。
