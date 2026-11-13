#!/usr/bin/env bash
# 在容器里打 blbl Debug APK，产物拷到仓库 dist/。本机不装 Android 编译工具链。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${BLBL_BUILD_IMAGE:-blbl-android-build:local}"
TASK="${1:-assembleDebug}"
OUT_DIR="${ROOT}/dist"
AAR="${ROOT}/app/libs/ijkplayer-cmake-release.aar"

cd "$ROOT"

if [ ! -f "$AAR" ]; then
  echo "找不到本地 AAR: $AAR"
  echo "请先把 ijkplayer-cmake-release.aar 放到 app/libs/"
  exit 1
fi

echo "==> 构建打包镜像: ${IMAGE}"
docker build --platform=linux/amd64 -t "${IMAGE}" -f docker/Dockerfile docker

echo "==> 容器内执行: ./gradlew ${TASK}"
# 挂源码读写：gradle 要写 build/。缓存放命名卷。
# 若本机有 local.properties（常见 Mac sdk.dir），构建期间改成容器内 SDK，结束还原。
docker run --rm --platform=linux/amd64 \
  -v "${ROOT}:/workspace" \
  -v blbl-gradle-cache:/root/.gradle \
  -w /workspace \
  -e ANDROID_SDK_ROOT=/opt/android-sdk \
  -e ANDROID_HOME=/opt/android-sdk \
  "${IMAGE}" \
  bash -lc '
    set -euo pipefail
    LP=local.properties
    HAD_LP=0
    if [ -f "$LP" ]; then
      HAD_LP=1
      cp "$LP" /tmp/local.properties.bak
    fi
    restore_lp() {
      if [ "$HAD_LP" = "1" ]; then
        cp /tmp/local.properties.bak "$LP"
      elif [ -f "$LP" ]; then
        # 容器内新建的，退出时删掉，避免弄脏工作区。
        rm -f "$LP"
      fi
    }
    trap restore_lp EXIT
    printf "sdk.dir=/opt/android-sdk\n" > "$LP"
    chmod +x ./gradlew
    ./gradlew '"${TASK}"' --no-daemon --build-cache --max-workers=2
  '

mkdir -p "${OUT_DIR}"
SRC_APK="${ROOT}/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$SRC_APK" ]; then
  echo "没有找到 APK: $SRC_APK"
  exit 1
fi

cp -f "$SRC_APK" "${OUT_DIR}/app-debug.apk"
echo "产出: ${OUT_DIR}/app-debug.apk ($(du -h "$SRC_APK" | awk '{print $1}'))"
echo "==> 完成。安装示例: ./scripts/run-emulator-install.sh"
