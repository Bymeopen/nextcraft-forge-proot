#!/usr/bin/env bash
set -euo pipefail

# Build the NextCraft Forge Android toolchain bundle (nextcraft-android-arm64-<version>.tar.zst): an overlay extracted
# at the root of the Ubuntu 24.04 ARM64 chroot rootfs, holding only root/, opt/, and usr/local/bin/.
#
# Contents and provenance (every download is verified against the checksums pinned below):
#   opt/<JDK_DIR>/                         Eclipse Temurin JDK aarch64 (api.adoptium.net), SHA-256 from Adoptium
#   opt/jdk-21, usr/local/bin/{java,...}   symlinks into the JDK
#   opt/gradle/gradle-<GRADLE_VERSION>/    Gradle binary distribution (services.gradle.org), SHA-256 from the .sha256 file
#   root/android-sdk/platforms/<PLATFORM>/ Android SDK Platform (dl.google.com, SHA-1 from repository2-3.xml + SHA-256)
#   root/android-sdk/build-tools/<BT>/     Java parts of the official Linux Build-Tools (d8, apksigner, lib/*.jar, ...)
#                                          plus the ARM64 native tools (aapt, aapt2, aidl, dexdump, split-select,
#                                          zipalign) from the lzhiyong/android-sdk-tools GitHub release: a third-party
#                                          build of the AOSP sources (NDK, static Bionic). Google does not publish
#                                          Linux ARM64 Build-Tools. The binaries are static: no /system/bin/linker64.
#   root/android-sdk/licenses/             android-sdk-license hash, as sdkmanager writes it
#   root/maven/localMvnRepository/         offline Maven repository from build-android-maven-cache.sh
#   root/.gradle/init.d/nextcraft-android.gradle, root/.gradle/gradle.properties, root/.nextcraft-android-tools-version
#                                          byte-identical to what RuntimeInstaller writes
#
# No root or chroot is needed to assemble the bundle. Set NEXTCRAFT_CORE_ROOTFS to an extracted Core bundle to also
# smoke-test the result inside a chroot copy (java, gradle, aapt2, d8); that needs root and, on x86_64, qemu-user-static
# with an aarch64 binfmt handler registered with the `F` flag.
#
#   ./build-android-bundle.sh
#   sudo NEXTCRAFT_CORE_ROOTFS=/path/to/core-rootfs NEXTCRAFT_MAVEN_REPO=dist/runtime-bundles/android-maven-2026.10.1 ./build-android-bundle.sh
#
# Environment:
#   NEXTCRAFT_ANDROID_BUNDLE_VERSION  bundle version (default 2026.10.1)
#   NEXTCRAFT_MAVEN_REPO     harvested Maven repository; when unset, build-android-maven-cache.sh is run first (x86_64 only)
#   NEXTCRAFT_OUT_DIR        output directory (default: dist/runtime-bundles, git-ignored)
#   NEXTCRAFT_WORK_DIR       parent for the mktemp work directory
#   NEXTCRAFT_KEEP_WORK      1 keeps the work directory
#   NEXTCRAFT_DOWNLOADS      cache directory for the downloaded archives (default: <work>/downloads)
#   NEXTCRAFT_CORE_ROOTFS    optional: extracted Core rootfs for the chroot smoke test (read only; a copy is used)
#
# Keep the versions and paths in sync with app/src/main/java/com/nextcraft/forge/runtime/AndroidToolchain.kt and
# scripts/runtime-bundles/chroot-health.sh.

BUNDLE_VERSION="${NEXTCRAFT_ANDROID_BUNDLE_VERSION:-2026.10.1}"
TOOLS_VERSION="sdk37.2-build-tools36-gradle9.8.1-jdk21-maven-2026.10"   # AndroidToolchain.TOOLS_VERSION
PLATFORM="android-37.2"
BUILD_TOOLS_VERSION="36.0.0"
GRADLE_VERSION="9.8.1"
JDK_MAJOR="21"
JDK_DIR="jdk-21.0.12.1+1"

# Temurin 21.0.12.1+1 aarch64 — https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=aarch64&image_type=jdk&os=linux
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.12.1_1.tar.gz"
JDK_SHA256="23e37e026f12f3e706f18938ff611db3032d075b09d0879a25d06718c773e223"
# Gradle — https://services.gradle.org/distributions/gradle-9.8.1-bin.zip.sha256
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_SHA256="dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f"
# Android SDK Platform 37.2 r01 — repository2-3.xml publishes SHA-1 only; SHA-256 computed from that archive.
PLATFORM_URL="https://dl.google.com/android/repository/platform-37.2_r01.zip"
PLATFORM_SHA1="4bec12a02162ff6852df50b5fcf8ee762b050966"
PLATFORM_SHA256="916410633df92a98da56aa7c87a1955f865f4d3b888373660794ef8173f72972"
# Android SDK Build-Tools 36.0.0 (Linux) — repository2-3.xml SHA-1; SHA-256 computed. Only the Java parts are used.
BUILD_TOOLS_URL="https://dl.google.com/android/repository/build-tools_r36_linux.zip"
BUILD_TOOLS_SHA1="b0b6376977657e8ad9b969bacf4093601da2c6fb"
BUILD_TOOLS_SHA256="5d9ac77fb6ff43d9da518a337b4fcf8f9097113df531d99ccefe80ef7ce8250b"
# ARM64 native tools: lzhiyong/android-sdk-tools release 35.0.2 (AOSP platform-tools-35.0.2 sources, NDK static build).
# Verified against AGP 9.4.1: the same-generation Google aapt2 (Build-Tools 35.0.1) builds the templates on x86_64.
NATIVE_TOOLS_URL="https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip"
NATIVE_TOOLS_SHA256="db1cea2c4454d5f9c5a802646b2d1cf560b4ee7badbe23e51ab8e1881bb50fc2"
NATIVE_TOOLS=(aapt aapt2 aidl dexdump split-select zipalign)
# Build-Tools files kept besides the native tools (everything else is x86_64-only: lld, llvm-rs-cc, renderscript, lib64).
BUILD_TOOLS_JAVA_FILES=(NOTICE.txt apksigner core-lambda-stubs.jar d8 lib runtime.properties source.properties)
# Hash sdkmanager writes to licenses/android-sdk-license after accepting the SDK license.
SDK_LICENSE_HASH="24333f8a63b6825ea9c5514f83c2829b004d1fee"

ARCHIVE="nextcraft-android-arm64-${BUNDLE_VERSION}.tar.zst"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="${NEXTCRAFT_OUT_DIR:-$ROOT_DIR/dist/runtime-bundles}"
WORK_PARENT="${NEXTCRAFT_WORK_DIR:-}"
KEEP_WORK="${NEXTCRAFT_KEEP_WORK:-0}"
MAVEN_REPO="${NEXTCRAFT_MAVEN_REPO:-}"
CORE_ROOTFS="${NEXTCRAFT_CORE_ROOTFS:-}"

for tool in curl unzip tar zstd python3 sha256sum sha1sum file; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done
[[ -z "$MAVEN_REPO" || -f "$MAVEN_REPO/MANIFEST.sha256" ]] || { echo "$MAVEN_REPO is not a build-android-maven-cache.sh output" >&2; exit 1; }
if [[ -n "$CORE_ROOTFS" ]]; then
  [[ "$(id -u)" == "0" ]] || { echo "The chroot smoke test needs root" >&2; exit 1; }
  grep -qx 'ubuntu-24.04.5-arm64' "$CORE_ROOTFS/.nextcraft-rootfs-version" "$CORE_ROOTFS/.pocket-rootfs-version" 2>/dev/null || { echo "$CORE_ROOTFS is not an extracted Core 24.04.5 rootfs" >&2; exit 1; }
  if [[ "$(uname -m)" != "aarch64" ]]; then
    grep -qs '^flags: .*F' /proc/sys/fs/binfmt_misc/qemu-aarch64 || {
      echo "qemu-aarch64 binfmt handler with the F flag is not registered (binfmt_misc is lost on container restart)" >&2
      exit 1
    }
  fi
fi

work="$(mktemp -d ${WORK_PARENT:+-p "$WORK_PARENT"})"
downloads="${NEXTCRAFT_DOWNLOADS:-$work/downloads}"
payload="$work/payload"
rootfs="$work/rootfs"
mounted=0
cleanup() {
  if [[ "$mounted" == "1" ]]; then
    umount -lf "$rootfs/dev" "$rootfs/proc" 2>/dev/null || true
  fi
  # Only delete the directory this script created itself via mktemp, and only when no mount is left inside it.
  if [[ "$KEEP_WORK" != "1" && -n "$work" && "$work" != "/" ]] && ! grep -q " $work/" /proc/mounts; then
    rm -rf --one-file-system -- "$work"
  else
    echo "Work directory kept: $work" >&2
  fi
}
trap cleanup EXIT
mkdir -p "$downloads" "$payload"

# fetch URL SHA256 [SHA1]: downloads into $downloads (reusing a verified copy) and verifies the pinned checksum(s).
fetch() {
  local url="$1" sha256="$2" sha1="${3:-}" name file
  name="$(basename "${url%%\?*}")"
  file="$downloads/$name"
  if [[ ! -f "$file" ]] || ! echo "$sha256  $file" | sha256sum -c --status; then
    echo "Downloading $name" >&2
    curl -fsSL --retry 3 -o "$file.part" "$url"
    mv "$file.part" "$file"
  fi
  echo "$sha256  $file" | sha256sum -c --status || { echo "SHA-256 mismatch: $name" >&2; exit 1; }
  [[ -z "$sha1" ]] || echo "$sha1  $file" | sha1sum -c --status || { echo "SHA-1 mismatch: $name" >&2; exit 1; }
  printf '%s' "$file"
}

jdk_tar="$(fetch "$JDK_URL" "$JDK_SHA256")"
gradle_zip="$(fetch "$GRADLE_URL" "$GRADLE_SHA256")"
platform_zip="$(fetch "$PLATFORM_URL" "$PLATFORM_SHA256" "$PLATFORM_SHA1")"
build_tools_zip="$(fetch "$BUILD_TOOLS_URL" "$BUILD_TOOLS_SHA256" "$BUILD_TOOLS_SHA1")"
native_zip="$(fetch "$NATIVE_TOOLS_URL" "$NATIVE_TOOLS_SHA256")"

if [[ -z "$MAVEN_REPO" ]]; then
  echo "Building the offline Maven repository"
  MAVEN_REPO="$work/maven"
  NEXTCRAFT_MAVEN_OUT="$MAVEN_REPO" NEXTCRAFT_WORK_DIR="$work" NEXTCRAFT_DOWNLOADS="$downloads" \
    "$SCRIPT_DIR/build-android-maven-cache.sh"
fi

echo "Laying out JDK and Gradle"
mkdir -p "$payload/opt/gradle" "$payload/usr/local/bin"
tar -xzf "$jdk_tar" -C "$payload/opt"
[[ -x "$payload/opt/$JDK_DIR/bin/java" ]] || { echo "JDK archive did not produce opt/$JDK_DIR" >&2; exit 1; }
# jlink modules (jmods), the JDK sources (lib/src.zip), and man pages are never used by Gradle or javac: ~140 MB saved.
rm -rf "$payload/opt/$JDK_DIR/jmods" "$payload/opt/$JDK_DIR/lib/src.zip" "$payload/opt/$JDK_DIR/man"
ln -s "$JDK_DIR" "$payload/opt/jdk-$JDK_MAJOR"
for bin in java javac jar jarsigner javadoc keytool; do
  ln -s "/opt/jdk-$JDK_MAJOR/bin/$bin" "$payload/usr/local/bin/$bin"
done
unzip -q "$gradle_zip" -d "$payload/opt/gradle"
[[ -f "$payload/opt/gradle/gradle-$GRADLE_VERSION/bin/gradle" ]] || { echo "Gradle archive did not produce gradle-$GRADLE_VERSION" >&2; exit 1; }

echo "Laying out the Android SDK"
sdk="$payload/root/android-sdk"
mkdir -p "$sdk/platforms" "$sdk/build-tools" "$sdk/licenses"
unzip -q "$platform_zip" -d "$work/platform"
platform_src="$(find "$work/platform" -mindepth 1 -maxdepth 1 -type d)"
[[ -f "$platform_src/android.jar" ]] || { echo "Platform archive has no android.jar" >&2; exit 1; }
mv "$platform_src" "$sdk/platforms/$PLATFORM"
unzip -q "$build_tools_zip" -d "$work/build-tools"
build_tools_src="$(find "$work/build-tools" -mindepth 1 -maxdepth 1 -type d)"
grep -qx "Pkg.Revision=$BUILD_TOOLS_VERSION" "$build_tools_src/source.properties" || { echo "Build-Tools archive is not $BUILD_TOOLS_VERSION" >&2; exit 1; }
build_tools="$sdk/build-tools/$BUILD_TOOLS_VERSION"
mkdir -p "$build_tools"
for entry in "${BUILD_TOOLS_JAVA_FILES[@]}"; do
  cp -a "$build_tools_src/$entry" "$build_tools/"
done
unzip -q "$native_zip" -d "$work/native"
for tool in "${NATIVE_TOOLS[@]}"; do
  cp "$work/native/build-tools/$tool" "$build_tools/$tool"
  chmod 755 "$build_tools/$tool"
done
chmod 755 "$build_tools/apksigner" "$build_tools/d8"
printf '\n%s\n' "$SDK_LICENSE_HASH" > "$sdk/licenses/android-sdk-license"

# package.xml is what AGP reads to recognise an installed package (sdkmanager writes it; a plain archive has none).
# The license text sdkmanager embeds is not required.
cat > "$sdk/platforms/$PLATFORM/package.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns6="http://schemas.android.com/sdk/android/repo/repository2/04">
  <localPackage path="platforms;${PLATFORM#android-}" obsolete="false">
    <type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns6:platformDetailsType">
      <api-level>${PLATFORM#android-}</api-level>
      <codename></codename>
      <extension-level>$(sed -n 's/^AndroidVersion.ExtensionLevel=//p' "$sdk/platforms/$PLATFORM/source.properties")</extension-level>
      <base-extension>true</base-extension>
      <layoutlib api="$(sed -n 's/^Layoutlib.Api=//p' "$sdk/platforms/$PLATFORM/source.properties")" />
    </type-details>
    <revision>
      <major>$(sed -n 's/^Pkg.Revision=//p' "$sdk/platforms/$PLATFORM/source.properties")</major>
    </revision>
    <display-name>Android SDK Platform ${PLATFORM#android-}</display-name>
  </localPackage>
</ns2:repository>
EOF
IFS=. read -r bt_major bt_minor bt_micro <<< "$BUILD_TOOLS_VERSION"
cat > "$build_tools/package.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns5="http://schemas.android.com/repository/android/generic/02">
  <localPackage path="build-tools;$BUILD_TOOLS_VERSION" obsolete="false">
    <type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns5:genericDetailsType" />
    <revision>
      <major>$bt_major</major>
      <minor>$bt_minor</minor>
      <micro>$bt_micro</micro>
    </revision>
    <display-name>Android SDK Build-Tools $bt_major</display-name>
  </localPackage>
</ns2:repository>
EOF

echo "Copying the offline Maven repository"
mkdir -p "$payload/root/maven"
cp -a "$MAVEN_REPO" "$payload/root/maven/localMvnRepository"
(cd "$payload/root/maven/localMvnRepository" && sha256sum --quiet -c MANIFEST.sha256) || { echo "Maven repository does not match its manifest" >&2; exit 1; }
rm "$payload/root/maven/localMvnRepository/MANIFEST.sha256"

echo "Writing Gradle configuration and marker"
mkdir -p "$payload/root/.gradle/init.d"
# Same text as RuntimeInstaller.ANDROID_GRADLE_INIT_SCRIPT and the managed lines of writeAndroidGradleConfiguration.
cat > "$payload/root/.gradle/init.d/nextcraft-android.gradle" <<EOF
def nextcraftMaven = uri('/root/maven/localMvnRepository')
// The bundled cache holds some POMs without their jars, and Gradle does not try another repository once it
// found the metadata. It goes first for offline builds and for NextCraft's own project templates (which it was
// built from); any other project resolves from the real repositories and uses it only as a last resort.
def nextcraftCacheFirst = { File root ->
    gradle.startParameter.offline || new File(root, '.nextcraft/android-project.json').isFile()
}
// sqlite-jdbc (Room's KSP/kapt schema checks) sees Android's /system in the chroot and loads its Android build,
// which cannot run on Ubuntu ("invalid ELF header"). Point it at the Linux aarch64 library from the same jar. On a
// project's first build the jar is not cached yet, so a Room project gets a pinned release from Maven Central instead.
def nextcraftSqliteJdbc = { File projectDir ->
    if (System.getProperty('org.sqlite.lib.path')) return
    def sqliteCache = new File(gradle.gradleUserHomeDir, 'caches/modules-2/files-2.1/org.xerial/sqlite-jdbc')
    def sqliteJars = []
    (sqliteCache.listFiles() ?: []).each { v -> (v.listFiles() ?: []).each { h ->
        (h.listFiles() ?: []).each { f -> if (f.name.startsWith('sqlite-jdbc-') && f.name.endsWith('.jar')) sqliteJars << f }
    } }
    def sqliteJar = sqliteJars.max { it.lastModified() }
    if (sqliteJar == null && !gradle.startParameter.offline) {
        def buildFiles = ['gradle/libs.versions.toml', 'build.gradle', 'build.gradle.kts', 'app/build.gradle', 'app/build.gradle.kts']
        def usesRoom = buildFiles.any { name ->
            def f = new File(projectDir, name)
            f.isFile() && f.text.contains('androidx.room')
        }
        def pinned = new File(gradle.gradleUserHomeDir, 'nextcraft/sqlite-jdbc-3.50.3.0.jar')
        if (usesRoom && !pinned.isFile()) {
            try {
                def connection = new URL('https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.50.3.0/sqlite-jdbc-3.50.3.0.jar').openConnection()
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                pinned.parentFile.mkdirs()
                def partial = new File(pinned.path + '.part')
                connection.inputStream.withStream { input -> partial.withOutputStream { it << input } }
                partial.renameTo(pinned)
            } catch (Exception ignored) {
                // Offline or blocked: the build still runs and the next one uses the jar Gradle downloaded.
            }
        }
        if (pinned.isFile()) sqliteJar = pinned
    }
    if (sqliteJar == null) return
    def sqliteDir = new File(gradle.gradleUserHomeDir, 'nextcraft/' + sqliteJar.name.replace('.jar', ''))
    def sqliteLib = new File(sqliteDir, 'libsqlitejdbc.so')
    if (!sqliteLib.isFile()) {
        def zip = new java.util.zip.ZipFile(sqliteJar)
        try {
            def entry = zip.getEntry('org/sqlite/native/Linux/aarch64/libsqlitejdbc.so')
            if (entry != null) {
                sqliteDir.mkdirs()
                zip.getInputStream(entry).withStream { input -> sqliteLib.withOutputStream { it << input } }
            }
        } finally {
            zip.close()
        }
    }
    if (sqliteLib.isFile()) {
        System.setProperty('org.sqlite.lib.path', sqliteDir.path)
        System.setProperty('org.sqlite.lib.name', 'libsqlitejdbc.so')
    }
}
beforeSettings { settings ->
    nextcraftSqliteJdbc(settings.settingsDir)
    settings.pluginManagement.repositories {
        if (nextcraftCacheFirst(settings.settingsDir)) maven { url = nextcraftMaven }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
settingsEvaluated { settings ->
    settings.dependencyResolutionManagement.repositories {
        maven { url = nextcraftMaven }
    }
}
gradle.beforeProject { project ->
    project.extensions.extraProperties.set(
        'android.aapt2FromMavenOverride',
        '/root/android-sdk/build-tools/$BUILD_TOOLS_VERSION/aapt2'
    )
    if (nextcraftCacheFirst(project.rootDir)) {
        project.buildscript.repositories {
            maven { url = nextcraftMaven }
        }
    }
}
EOF
cat > "$payload/root/.gradle/gradle.properties" <<EOF
android.aapt2FromMavenOverride=/root/android-sdk/build-tools/$BUILD_TOOLS_VERSION/aapt2
org.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8
org.gradle.workers.max=2
org.gradle.parallel=false
kotlin.compiler.execution.strategy=in-process
EOF
printf '%s' "$TOOLS_VERSION" > "$payload/root/.nextcraft-android-tools-version"

echo "Checking the payload"
for tool in "${NATIVE_TOOLS[@]}"; do
  desc="$(file -b "$build_tools/$tool")"
  [[ "$desc" == *"ELF 64-bit LSB executable, ARM aarch64"* && "$desc" == *"statically linked"* ]] || {
    echo "$tool is not a static ARM64 executable: $desc" >&2; exit 1; }
done
[[ "$(file -b "$payload/opt/$JDK_DIR/bin/java")" == *"ARM aarch64"* ]] || { echo "JDK is not aarch64" >&2; exit 1; }
[[ "$(ls -A "$payload" | sort | tr '\n' ' ')" == "opt root usr " ]] || { echo "Unexpected top-level entries: $(ls -A "$payload")" >&2; exit 1; }
! find "$payload" -name 'qemu-*' -o -name '*.pem' -o -name '.netrc' -o -name 'gradle.properties' -path '*/caches/*' | grep -q . || { echo "Build host files leaked into the payload" >&2; exit 1; }
[[ ! -e "$payload/root/android-sdk/platform-tools" ]]

if [[ -n "$CORE_ROOTFS" ]]; then
  echo "Smoke-testing in a chroot copy of the Core rootfs"
  cp -a "$CORE_ROOTFS" "$rootfs"
  cp -a "$payload/." "$rootfs/"
  mount --bind /dev "$rootfs/dev"
  mount -t proc proc "$rootfs/proc"
  mounted=1
  guest() {
    chroot "$rootfs" /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 GLIBC_TUNABLES=glibc.pthread.rseq=0 \
      ANDROID_HOME=/root/android-sdk ANDROID_SDK_ROOT=/root/android-sdk GRADLE_USER_HOME=/root/.gradle \
      GRADLE_HOME="/opt/gradle/gradle-$GRADLE_VERSION" \
      PATH="/opt/gradle/gradle-$GRADLE_VERSION/bin:/root/android-sdk/build-tools/$BUILD_TOOLS_VERSION:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      /bin/bash -lc "$1"
  }
  guest "java -version 2>&1 | grep -E '\"$JDK_MAJOR\\.' && javac -version && keytool -help >/dev/null"
  guest "gradle --version --offline | grep '^Gradle $GRADLE_VERSION'"
  guest "aapt2 version && d8 --version && apksigner --version && zipalign 2>&1 | grep -qi usage"
  guest "test -f /root/android-sdk/platforms/$PLATFORM/android.jar"
  umount -lf "$rootfs/dev" "$rootfs/proc"
  mounted=0
fi

mkdir -p "$OUT_DIR"
python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$payload" "$work/payload.tar"
zstd -19 -T0 -f "$work/payload.tar" -o "$OUT_DIR/$ARCHIVE"
sha256sum "$OUT_DIR/$ARCHIVE"
wc -c "$OUT_DIR/$ARCHIVE" "$work/payload.tar"
echo "Android bundle created: $OUT_DIR/$ARCHIVE"
