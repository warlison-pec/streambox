# Ambiente de compilação: Gradle 8.11 + JDK 17 + Android SDK 35
FROM gradle:8.11.1-jdk17

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

RUN apt-get update && apt-get install -y --no-install-recommends unzip wget \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p $ANDROID_HOME/cmdline-tools \
 && wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/tools.zip \
 && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools \
 && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/tools.zip \
 && yes | sdkmanager --licenses > /dev/null \
 && sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"

WORKDIR /src
