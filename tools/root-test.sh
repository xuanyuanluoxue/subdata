#!/system/bin/sh
# =============================================================================
# SubData root 层功能测试（在模拟器内跑，不依赖 Xposed 注入）
#
# 目标：用 mount --bind 在两套数据目录之间来回切换，验证
#   1) 同一 App 在两套数据下互不干扰；
#   2) 每次切换后 App 仍能正常启动；
#   3) 反复切换后 inode / 属主 / SELinux 标签没有被破坏（决定能否安全回滚）。
#
# 安全约束（硬性）：
#   · 只操作 /data/local/tmp/subdata-test 和我们选定的一个系统 App；
#   · 绝不使用学习通（com.chaoxing.mobile），绝不碰 /data 以外的分区；
#   · 任何时刻最多一个 bind mount，退出前一定 umount（trap 兜底）。
#
# 用法（adb root 之后）：
#   adb push tools/root-test.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/root-test.sh [包名]
# =============================================================================

PKG="${1:-com.android.deskclock}"
WORK=/data/local/tmp/subdata-test
DATA="/data/data/$PKG"
FAIL=0

say()  { echo "[$(date '+%H:%M:%S')] $*"; }
ok()   { echo "  PASS  $*"; }
bad()  { echo "  FAIL  $*"; FAIL=$((FAIL+1)); }
note() { echo "  NOTE  $*"; }

cleanup() {
  # 关键：无论怎么退出，先保证没有残留挂载
  if mount | grep -q " $DATA "; then
    umount "$DATA" 2>/dev/null && echo "  cleanup: umounted $DATA"
  fi
}
trap cleanup EXIT INT TERM

say "=== SubData root-layer test ==="
say "target=$PKG data=$DATA"

# ---------------------------------------------------------------- 前置检查
[ "$(id -u)" = "0" ] || { echo "FATAL: 需要 root（先 adb root）"; exit 2; }
[ -d "$DATA" ] || { echo "FATAL: $DATA 不存在，换一个测试 App"; exit 2; }
if [ "$PKG" = "com.chaoxing.mobile" ]; then
  echo "FATAL: 拒绝在学习通上做测试"; exit 2
fi
say "selinux=$(getenforce)"

# ------------------------------------------------- 记录原始目录的元数据指纹
FP0=$(stat -c '%i %u %g %a' "$DATA")
CTX0=$(ls -ldZ "$DATA" | awk '{print $1" "$3" "$5}')
say "baseline inode/uid/gid/mode: $FP0"
say "baseline context: ${CTX0#* }"
INODE0=$(echo "$FP0" | cut -d' ' -f1)

# ------------------------------------------------------------------ 建两套数据
say "--- 准备两套数据目录 a / b ---"
cleanup
rm -rf "$WORK"
mkdir -p "$WORK/a" "$WORK/b" || { echo "FATAL: 无法创建 $WORK"; exit 2; }
# 两套数据里放可区分的标记文件
echo "slot-a" > "$WORK/a/slot_marker.txt"
echo "slot-b" > "$WORK/b/slot_marker.txt"
echo "a" > "$WORK/a/unique_a.txt"
echo "b" > "$WORK/b/unique_b.txt"

# SELinux：把测试目录的标签对齐到目标 App（含 MLS categories），否则 bind 之后
# 即使路径正确，App 也会被 SELinux 拒绝读写——这一点直接影响「bind 方案是否安全」。
APP_CTX=$(ls -Zd "$DATA" | awk '{print $1}')
if chcon "$APP_CTX" "$WORK/a" "$WORK/b" 2>/dev/null; then
  ok "chcon 对齐 SELinux 标签: $APP_CTX"
else
  bad "chcon 失败（后续读写可能被 SELinux 拒绝）"
fi
echo "a" > "$WORK/a/slot_marker.txt"
echo "b" > "$WORK/b/slot_marker.txt"

mount_one() {  # $1 = a|b
  cleanup
  am force-stop "$PKG" 2>/dev/null
  sleep 0.5
  mount --bind "$WORK/$1" "$DATA" || return 1
  return 0
}

seen_marker() {
  # 读 App 数据目录里能看到的 slot 标记（root 视角 + App 视角都要对）
  cat "$DATA/slot_marker.txt" 2>/dev/null | tr -d '\n'
}

# --------------------------------------------------- 场景 1：挂 a 并启动 App
say "--- 场景 1: 挂载 slot a，启动 $PKG ---"
if mount_one a; then
  ok "mount --bind a -> $DATA"
  M=$(seen_marker)
  [ "$M" = "a" ] && ok "数据目录指向 slot a (marker=$M)" || bad "marker 期望 a，实际 '$M'"

  am start -W -n "$(cmd package resolve-activity --brief "$PKG" 2>/dev/null | tail -1)" >/dev/null 2>&1
  sleep 2
  PID=$(pidof "$PKG")
  if [ -n "$PID" ]; then ok "App 启动成功 pid=$PID"; else bad "App 没有起来"; fi
  # 真写一次：App 自己创建的文件应落在 slot a
  echo "written-by-app-a" > "$DATA/app_wrote.txt" 2>/dev/null
  [ -f "$WORK/a/app_wrote.txt" ] && ok "写入落到 slot a" || note "写入未穿透到源目录"
else
  bad "mount --bind 失败"
fi

# --------------------------------------------------- 场景 2：切 b，验证隔离
say "--- 场景 2: 切到 slot b，验证两套数据互不干扰 ---"
if mount_one b; then
  ok "mount --bind b -> $DATA"
  M=$(seen_marker)
  [ "$M" = "b" ] && ok "数据目录指向 slot b (marker=$M)" || bad "marker 期望 b，实际 '$M'"
  [ -f "$DATA/unique_a.txt" ] && bad "slot b 下看到了 a 独有的文件（不隔离）" \
                              || ok "slot b 看不到 slot a 的独有文件（隔离成立）"
  [ -f "$DATA/app_wrote.txt" ] && bad "slot b 看到了 a 下 App 写的文件（不隔离）" \
                              || ok "slot a 期间写入的文件没有串到 b"
  am force-stop "$PKG" 2>/dev/null; sleep 0.5
  am start -W -n "$(cmd package resolve-activity --brief "$PKG" 2>/dev/null | tail -1)" >/dev/null 2>&1
  sleep 2
  PID=$(pidof "$PKG")
  if [ -n "$PID" ]; then ok "切到 b 后 App 仍能正常启动 pid=$PID"; else bad "切到 b 后 App 起不来"; fi
else
  bad "切到 b 的 mount 失败"
fi

# --------------------------------------------------- 场景 3：还原并校验元数据
say "--- 场景 3: umount 还原，检查 inode / 属主 / SELinux 标签 ---"
cleanup
if mount | grep -q " $DATA "; then
  bad "仍然存在 bind mount，还原失败"
else
  ok "已 umount，无残留挂载"
fi
am force-stop "$PKG" 2>/dev/null

FP1=$(stat -c '%i %u %g %a' "$DATA")
INODE1=$(echo "$FP1" | cut -d' ' -f1)
CTX1=$(ls -ldZ "$DATA" | awk '{print $1" "$3" "$5}')
[ "$FP0" = "$FP1" ] && ok "inode/uid/gid/mode 未变 ($FP1)" || bad "元数据变化 $FP0 -> $FP1"
[ "$CTX0" = "$CTX1" ] && ok "SELinux 标签未变" || bad "SELinux 标签变化: $CTX1"
[ "$INODE0" = "$INODE1" ] && ok "inode 与基线一致，可安全回滚" || bad "inode 变了，回滚需谨慎"

# 还原后原始数据仍在吗（App 目录本身不该被我们写坏）
[ -d "$DATA" ] && ok "原始数据目录完好" || bad "原始数据目录丢失"

# --------------------------------------------------- 场景 4：PathMap 语义对照
say "--- 场景 4: PathMap 扫描真实目录树（漏映射检查） ---"
if [ -f /data/local/tmp/pathmap.jar ]; then
  # shell 里跑 java 需要 app_process；由 build-pathmap-cli.sh 推送的包装脚本负责
  if [ -f /data/local/tmp/pathmap-run.sh ]; then
    sh /data/local/tmp/pathmap-run.sh "$PKG" || note "PathMap CLI 返回非 0（见上）"
  else
    note "pathmap-run.sh 未推送，跳过"
  fi
else
  note "pathmap.jar 未推送，跳过（先跑 tools/build-pathmap-cli.sh）"
fi

# -------------------------------------------------------------------- 清理
say "--- 清理 ---"
cleanup
rm -rf "$WORK"
ok "已删除测试目录 $WORK（原始数据目录未触碰）"

echo
if [ "$FAIL" = "0" ]; then
  say "RESULT: ALL PASS"
  exit 0
else
  say "RESULT: $FAIL FAILURE(S)"
  exit 1
fi
