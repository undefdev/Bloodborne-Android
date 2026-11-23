#!/usr/bin/env bash
# Host-JDK tests of the app's Java mod/PKG code (ModLoader, ModManager, PkgInstaller, GameCheck, Sfo, MiniJson), FootstepTracks,
# the Kishi's reports and idle reset (KishiReports, KishiIdleReset), the workbench's haptics design (the effect player
# from hapticsBench's player: Effect, Profile, FrameEncoder, Renderer, Scheduler, with its tests run by TestRunner; and
# HapticsTable, GameHaptics, HapticEvent in HapticsTest), of RuntimeCleanup
# (the removal of the former Linux runtime on an update) and the
# parity check against the Python they replace (tools/android/mods_android.py).
#   tests/android-mods/run.sh            unit tests, then the parity check
# Optional: BB_TEST_MOD_PKG=<1.09 data-mod .pkg> BB_TEST_GAME=<game folder, read only> for the real-PKG test
# (needs out/host/pkg-tool/ps4-pkg-tool: tools/android/build_pkg_tool.sh host);
# BB_TEST_HAPTICS=<a haptics.json export> to play every rule of a real export.
set -euo pipefail
cd -- "$(dirname -- "$0")/../.."
root=$PWD
jdk="$root/.local-deps/android/jdk/usr/lib/jvm/java-27-openjdk"
[[ -x $jdk/bin/javac ]] || jdk=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
out=${BB_TEST_OUT:-$root/out/android-mods-test}
rm -rf "$out"; mkdir -p "$out/classes"
src=tools/android/frontend/src/com/ducvd89/bloodborne
# The core classes are plain Java 8, as the frontend's javac --release 8 builds them.
"$jdk/bin/javac" --release 8 -Xlint:-options -d "$out/classes" \
    $src/FsUtil.java $src/SafeWrite.java $src/MiniJson.java $src/ModLoader.java $src/ModManager.java $src/Sfo.java \
    $src/GameCheck.java $src/PkgInstaller.java $src/PkgVerifier.java $src/PkgProgress.java $src/RuntimeCleanup.java $src/FootstepTracks.java \
    $src/KishiReports.java $src/KishiIdleReset.java \
    $src/Effect.java $src/Profile.java $src/FrameEncoder.java $src/Renderer.java $src/Scheduler.java \
    $src/HapticsTable.java $src/GameHaptics.java $src/HapticEvent.java $src/EventRoute.java
"$jdk/bin/javac" --release 17 -cp "$out/classes" -d "$out/classes" tests/android-mods/T.java tests/android-mods/ModsTest.java \
    tests/android-mods/ParityCli.java tests/android-mods/CleanupTest.java tests/android-mods/SafeWriteTest.java tests/android-mods/FootstepTracksTest.java \
    tests/android-mods/KishiTest.java \
    tests/android-mods/Check.java tests/android-mods/Fx.java tests/android-mods/TestRunner.java tests/android-mods/FrameEncoderTest.java \
    tests/android-mods/RendererTest.java tests/android-mods/SchedulerTest.java tests/android-mods/HapticsTest.java
"$jdk/bin/java" -cp "$out/classes" com.ducvd89.bloodborne.ModsTest
"$jdk/bin/java" -cp "$out/classes" com.ducvd89.bloodborne.CleanupTest
"$jdk/bin/java" -cp "$out/classes" com.ducvd89.bloodborne.SafeWriteTest
"$jdk/bin/java" -cp "$out/classes" com.ducvd89.bloodborne.FootstepTracksTest
"$jdk/bin/java" -cp "$out/classes" com.ducvd89.bloodborne.KishiTest
"$jdk/bin/java" -cp "$out/classes" com.ducvd89.bloodborne.TestRunner com.ducvd89.bloodborne.FrameEncoderTest \
    com.ducvd89.bloodborne.RendererTest com.ducvd89.bloodborne.SchedulerTest
"$jdk/bin/java" -cp "$out/classes" com.ducvd89.bloodborne.HapticsTest
python3 -I tests/android-mods/parity.py "$jdk/bin/java" "$out/classes"
