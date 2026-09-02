# Copy to env.sh, set JAVA_HOME and ANDROID_HOME in this shell, then source it.
# Verified toolchain: JDK 21, Android SDK 36, NDK 28.2.13676358, CMake 3.31.6.
: "${JAVA_HOME:?Set JAVA_HOME to your JDK directory first}"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory first}"
export JAVA_HOME ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
