#!/system/bin/sh
# 在设备里用 app_process 跑 PathMap CLI（需要 root，才能读 /data/data/<pkg>）。
PKG="${1:-com.android.deskclock}"
BASE="/data/data/$PKG"
if [ ! -d "$BASE" ]; then
  echo "FATAL: $BASE not found"
  exit 2
fi
if [ "$PKG" = "com.chaoxing.mobile" ]; then
  echo "FATAL: 不拿学习通做测试"
  exit 2
fi
exec app_process -Djava.class.path=/data/local/tmp/pathmap.jar /system/bin \
  com.xavier.subdata.PathMap a "$BASE,/data/user/0/$PKG" --root "$BASE" --max 5000
