#!/usr/bin/env bash
# 启动本机 Android TV 模拟器，并安装 dist/ 里的 Debug APK。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
AVD_NAME="${BLBL_AVD:-TVBox_ATV_33}"
APK="${1:-$ROOT/dist/app-debug.apk}"
PKG="blbl.cat3399"
LAUNCH_COMPONENT="${PKG}/${PKG}.ui.MainLauncherDarkAlias"

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export PATH="${SDK_ROOT}/platform-tools:${SDK_ROOT}/emulator:${SDK_ROOT}/cmdline-tools/latest/bin:${PATH}"

if [ ! -x "${SDK_ROOT}/emulator/emulator" ]; then
  echo "找不到模拟器: ${SDK_ROOT}/emulator/emulator"
  exit 1
fi
if [ ! -f "$APK" ]; then
  echo "找不到 APK: $APK"
  echo "先跑: ./scripts/docker-build-apk.sh"
  exit 1
fi

# 已有在线设备则复用。
if ! adb devices | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
  echo "==> 启动模拟器 ${AVD_NAME}"
  # 双重 fork 脱离当前 shell 进程组，避免 IDE/Agent 结束会话时把模拟器一起杀掉。
  # BLBL_EMU_NO_WINDOW=1 时无头；BLBL_EMU_NO_AUDIO=1 时关声音（默认开声音方便点测）。
  NO_WINDOW="${BLBL_EMU_NO_WINDOW:-0}" NO_AUDIO="${BLBL_EMU_NO_AUDIO:-0}" python3 - "$SDK_ROOT" "$AVD_NAME" <<'PY'
import os, sys, subprocess
sdk, avd = sys.argv[1], sys.argv[2]
emu = os.path.join(sdk, "emulator", "emulator")
# QtRawKeyboardInput：ATV 镜像上默认键盘转发经常丢 Esc/返回。
args = [
    emu, "-avd", avd,
    "-netdelay", "none", "-netspeed", "full",
    "-no-snapshot",
    "-gpu", "swiftshader_indirect",
    "-feature", "QtRawKeyboardInput",
]
if os.environ.get("NO_AUDIO") == "1":
    args.append("-no-audio")
if os.environ.get("NO_WINDOW") == "1":
    args += ["-no-window", "-no-boot-anim"]
log = open("/tmp/blbl-emulator.log", "w")
if os.fork() > 0:
    raise SystemExit(0)
os.setsid()
if os.fork() > 0:
    raise SystemExit(0)
os.chdir("/")
os.umask(0)
devnull = open(os.devnull, "r")
os.dup2(devnull.fileno(), 0)
os.dup2(log.fileno(), 1)
os.dup2(log.fileno(), 2)
env = os.environ.copy()
env["ANDROID_SDK_ROOT"] = sdk
env["ANDROID_HOME"] = sdk
proc = subprocess.Popen(args, env=env, close_fds=True)
open("/tmp/blbl-emulator.pid", "w").write(str(proc.pid))
raise SystemExit(0)
PY
  sleep 1
  echo "模拟器 pid=$(cat /tmp/blbl-emulator.pid 2>/dev/null || echo '?') 日志=/tmp/blbl-emulator.log"
fi

echo "==> 等待 adb device / boot_completed"
for i in $(seq 1 90); do
  st="$(adb devices 2>/dev/null | awk '/emulator-/{print $2; exit}')"
  boot="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [ "$st" = "device" ] && [ "$boot" = "1" ]; then
    break
  fi
  sleep 2
done
st="$(adb devices 2>/dev/null | awk '/emulator-/{print $2; exit}')"
boot="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
if [ "$st" != "device" ] || [ "$boot" != "1" ]; then
  echo "模拟器启动超时，看 /tmp/blbl-emulator.log"
  exit 1
fi

echo "==> 安装 $APK"
adb install -r -t "$APK"
echo "==> 启动 MainLauncherDarkAlias"
adb shell am start -n "$LAUNCH_COMPONENT" || \
  adb shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1
# am start 返回后进程可能还没就绪，稍等再取 pid。
app_pid=""
for _ in $(seq 1 10); do
  app_pid="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
  if [ -n "$app_pid" ]; then
    break
  fi
  sleep 0.5
done
echo "==> app_pid=${app_pid:-?}"
if [ -z "$app_pid" ]; then
  echo "警告: 未检测到进程，应用可能启动失败"
  exit 1
fi
echo "==> 完成"
