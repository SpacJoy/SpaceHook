#!/usr/bin/env bash
# ============================================================================
# SpaceHook 一键部署脚本
# ----------------------------------------------------------------------------
# 用法：
#   ./tools/deploy.sh pair   <ip:配对端口> <配对码>   # 首次配对（配对码有时效）
#   ./tools/deploy.sh conn   <ip:端口>                # 连接（无线调试界面显示的端口）
#   ./tools/deploy.sh install                         # 安装 APK 到已连接设备
#   ./tools/deploy.sh verify                          # 安装后校验
#
# 例：
#   ./tools/deploy.sh pair 192.168.31.5:37000 201214
#   ./tools/deploy.sh conn 192.168.31.5:5555
#   ./tools/deploy.sh install
# ============================================================================
set -uo pipefail

SDK="D:/Code/Android/SDK"
ADB="$SDK/platform-tools/adb.exe"
PROJECT="D:/Code/Android/Projects/LSPosed/Xposed.spacejoy.top"
APK="$PROJECT/app/build/outputs/apk/debug/app-debug.apk"
MODULE_PKG="top.spacejoy.spacehook"

log()  { printf "\033[36m[%s]\033[0m %s\n" "$(date +%H:%M:%S)" "$*"; }
ok()   { printf "\033[32m  ✓ %s\033[0m\n" "$*"; }
warn() { printf "\033[33m  ! %s\033[0m\n" "$*"; }
err()  { printf "\033[31m  ✗ %s\033[0m\n" "$*"; }

require_adb() {
    [ -x "$ADB" ] || { err "找不到 adb: $ADB"; exit 1; }
}

cmd_pair() {
    require_adb
    local target="${1:-}" code="${2:-}"
    [ -n "$target" ] && [ -n "$code" ] || { err "用法: deploy.sh pair <ip:配对端口> <配对码>"; exit 1; }
    log "配对 $target（配对码有效期很短，需尽快执行）"
    "$ADB" kill-server >/dev/null 2>&1; sleep 1
    "$ADB" pair "$target" "$code" 2>&1 | sed 's/^/  /'
}

cmd_conn() {
    require_adb
    local target="${1:-}"
    [ -n "$target" ] || { err "用法: deploy.sh conn <ip:端口>"; exit 1; }
    log "连接 $target"
    "$ADB" connect "$target" 2>&1 | sed 's/^/  /'
    echo
    "$ADB" devices -l 2>&1 | sed 's/^/  /'
}

cmd_install() {
    require_adb
    [ -f "$APK" ] || { err "APK 不存在，请先构建：gradle :app:assembleDebug"; exit 1; }
    local count
    count=$("$ADB" devices | grep -c "device$" || true)
    if [ "$count" -eq 0 ]; then
        err "没有已连接的设备。先执行 deploy.sh conn <ip:端口>"
        exit 1
    fi
    log "安装 $APK ($(du -h "$APK" | cut -f1))"
    "$ADB" install -r -t "$APK" 2>&1 | sed 's/^/  /'
}

cmd_verify() {
    require_adb
    log "校验安装结果"
    if "$ADB" shell pm list packages 2>/dev/null | grep -q "$MODULE_PKG"; then
        ok "模块已安装: $MODULE_PKG"
    else
        err "未找到 $MODULE_PKG"
        return 1
    fi

    echo
    log "设备信息"
    "$ADB" shell getprop ro.product.model 2>/dev/null | sed 's/^/  /'
    "$ADB" shell getprop ro.build.version.release 2>/dev/null | sed 's/^/  /'
    "$ADB" shell getprop ro.build.version.sdk 2>/dev/null | sed 's/^/  /'

    echo
    log "模块声明（应能看到 META-INF/xposed/* 三个文件）"
    "$ADB" shell pm path "$MODULE_PKG" 2>/dev/null | sed 's/^/  /'

    echo
    warn "接下来需手动操作："
    echo "    1. 打开 LSPosed → 模块 → 启用 SpaceHook"
    echo "    2. 勾选作用域（系统界面 / 系统界面组件 / 录音机 / 123云盘）"
    echo "    3. 重启目标应用（或重启系统界面）"
    echo "    4. 打开 SpaceHook，首页应显示「模块已激活」"
    echo
    log "日志过滤命令"
    echo "    $ADB logcat -s SpaceHook:V Xposed:V"
}

case "${1:-}" in
    pair)    shift; cmd_pair "$@" ;;
    conn)    shift; cmd_conn "$@" ;;
    install) cmd_install ;;
    verify)  cmd_verify ;;
    *)       sed -n '2,16p' "$0" ;;
esac
