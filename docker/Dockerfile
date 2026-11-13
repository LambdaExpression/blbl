# blbl 打包镜像：JDK 17 + AGP 8.5 / Gradle 8.7 + compileSdk 36
# Apple Silicon 上用 linux/amd64，避免 arm64 缺 Android 组件。
FROM --platform=linux/amd64 docker.m.daocloud.io/library/eclipse-temurin:17-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip git \
    && rm -rf /var/lib/apt/lists/*

# 安装 Android cmdline-tools，预拉 platform / build-tools。
ARG CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools \
    && curl -fsSL -o /tmp/cmdline-tools.zip "${CMDLINE_TOOLS_URL}" \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools \
    && mv /tmp/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest \
    && rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip \
    && export JAVA_HOME=/opt/java/openjdk \
    && export PATH="${JAVA_HOME}/bin:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${PATH}" \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager --sdk_root=${ANDROID_SDK_ROOT} \
        "platforms;android-36" \
        "build-tools;35.0.0" \
        "platform-tools"

ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

WORKDIR /workspace

CMD ["./gradlew", "assembleDebug", "--no-daemon", "--build-cache"]
