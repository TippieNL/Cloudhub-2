#!/usr/bin/env bash
#
# Build the CloudHub Android app.
#
#   tools/build-apk.sh
#
# Idempotent: the SDK is installed only if missing, and the signing keystore is
# generated only once. Re-running it just rebuilds.
#
# The APK is a *client*. CloudHub is a PHP server application, so the app asks
# for your server's address on first launch rather than having one baked in.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-$ROOT/android-sdk}"
CMDLINE_VERSION="commandlinetools-linux-11076708_latest.zip"

# The wrapper JVM options this environment injects break sdkmanager's own
# argument parsing; the build does not need them.
export JAVA_TOOL_OPTIONS=""
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

# ---- 1. the SDK ------------------------------------------------------------
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "==> Installing the Android command-line tools into $SDK"
    mkdir -p "$SDK/cmdline-tools"
    curl -sSL -o /tmp/cmdline-tools.zip "https://dl.google.com/android/repository/$CMDLINE_VERSION"
    rm -rf /tmp/cmdline-tools-unpack
    unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-unpack
    mv /tmp/cmdline-tools-unpack/cmdline-tools "$SDK/cmdline-tools/latest"
    rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-unpack
fi

if [ ! -d "$SDK/platforms/android-34" ]; then
    echo "==> Accepting licences and installing the platform"
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses > /dev/null
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
        "platform-tools" "platforms;android-34" "build-tools;34.0.0" > /dev/null
fi

# ---- 2. signing ------------------------------------------------------------
# A keystore committed to a repository is a published signing key, so both the
# keystore and its passwords are gitignored. Losing them only means the next
# build is a different app identity -- reinstall rather than update.
KEYSTORE="$ROOT/android/keystore.jks"
PROPS="$ROOT/android/keystore.properties"
if [ ! -f "$KEYSTORE" ]; then
    echo "==> Generating a signing keystore"
    PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24)"
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" -alias cloudhub \
        -keyalg RSA -keysize 4096 -validity 10000 \
        -storepass "$PASSWORD" -keypass "$PASSWORD" \
        -dname "CN=CloudHub, OU=Self-hosted, O=CloudHub, C=NL" > /dev/null 2>&1
    cat > "$PROPS" <<PROPEOF
storeFile=keystore.jks
storePassword=$PASSWORD
keyAlias=cloudhub
keyPassword=$PASSWORD
PROPEOF
    chmod 600 "$PROPS"
    echo "    keystore.jks and keystore.properties written (both gitignored -- back them up)"
fi

# ---- 3. icons --------------------------------------------------------------
echo "==> Regenerating icons"
php "$ROOT/tools/make-icons.php" > /dev/null

# ---- 4. build --------------------------------------------------------------
echo "==> Building"
cd "$ROOT/android"
gradle --no-daemon --console=plain assembleRelease

APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "Build produced no APK"; exit 1; }

# ---- 5. verify -------------------------------------------------------------
BUILD_TOOLS="$SDK/build-tools/34.0.0"
echo
echo "==> Verifying"
"$BUILD_TOOLS/apksigner" verify --print-certs "$APK" | sed 's/^/    /'
"$BUILD_TOOLS/aapt2" dump badging "$APK" | grep -E "^(package|application-label|sdkVersion|targetSdkVersion|uses-permission)" | sed 's/^/    /'

echo
echo "Built $(du -h "$APK" | cut -f1)  ->  $APK"
