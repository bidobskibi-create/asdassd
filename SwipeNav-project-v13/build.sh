#!/bin/bash
# Usage: ./build.sh
# Needs: JDK 8+, Android SDK (platforms/android-19 or any, build-tools w/ d8 or dx+aapt+apksigner), zip
set -e
SDK="${ANDROID_HOME:?set ANDROID_HOME}"
JAR=$(ls -d $SDK/platforms/android-*/android.jar | head -1)
BT=$(ls -d $SDK/build-tools/* | tail -1)
rm -rf out && mkdir -p out/cls
javac -source 8 -target 8 -bootclasspath "$JAR" -d out/cls src/com/archdroid/swipenav/*.java
$BT/d8 --min-api 19 --output out $(find out/cls -name '*.class')
$BT/aapt package -f -M AndroidManifest.xml -I "$JAR" -F out/unsigned.apk
(cd out && zip -q unsigned.apk classes.dex)
keytool -genkey -keystore out/k.jks -storepass swipenav -keypass swipenav -alias k -keyalg RSA -keysize 2048 -validity 10000 -dname CN=SwipeNav
$BT/apksigner sign --ks out/k.jks --ks-pass pass:swipenav --v1-signing-enabled true --v2-signing-enabled false --out out/SwipeNav.apk out/unsigned.apk
# small TWRP-flashable update zip (flash it on top of the ROM)
rm -rf out/fz && cp -r flashable out/fz && mkdir -p out/fz/system/priv-app && cp out/SwipeNav.apk out/fz/system/priv-app/
(cd out/fz && zip -qr ../SwipeNav-flash.zip .)
echo "Done: out/SwipeNav-flash.zip  (copy to the Lumia 520, flash in TWRP)"
