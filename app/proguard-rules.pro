# Xposed 入口类由 assets/xposed_init 按名字加载，不能被裁剪/改名。
-keep class com.xavier.subdata.SubDataModule { *; }
-keep class de.robv.android.xposed.** { *; }
