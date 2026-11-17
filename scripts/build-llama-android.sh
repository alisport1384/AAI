#!/usr/bin/env bash
# Rebuilds libhushyar_llama.so from llama.cpp for every shipped ABI and copies it
# into app/src/main/jniLibs/. Output is committed, so this only needs to run when
# the pinned llama.cpp version changes.
#
#   LLAMA_VERSION=v1.0.0            (pinned upstream tag)
#   ANDROID_NDK_HOME=/path/to/ndk   (r27+)
#   CMAKE=/path/to/cmake            (3.22+)
#
# Usage: bash scripts/build-llama-android.sh
set -euo pipefail

LLAMA_VERSION="${LLAMA_VERSION:-v1.0.0}"
ABIS="${ABIS:-arm64-v8a x86_64}"
MIN_SDK="${MIN_SDK:-26}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-$HOME/.cache/android-sdk/ndk/27.2.12479018}"
CMAKE="${CMAKE:-$HOME/.cache/android-sdk/cmake/3.22.1/bin/cmake}"
SRC="${SRC:-${BUILD_ROOT:-$HOME/build}/src/llama.cpp-$LLAMA_VERSION}"
BUILD_ROOT="${BUILD_ROOT:-$HOME/build}"  # never /tmp: it may be a small tmpfs
OUT="$ROOT/app/src/main/jniLibs"

[[ -d "$NDK" ]] || { echo "NDK not found at $NDK (set ANDROID_NDK_HOME)" >&2; exit 1; }
[[ -x "$CMAKE" ]] || { echo "cmake not found at $CMAKE (set CMAKE)" >&2; exit 1; }

if [[ ! -d "$SRC" ]]; then
  echo "==> cloning llama.cpp $LLAMA_VERSION"
  git clone --depth 1 --branch "$LLAMA_VERSION" https://github.com/ggml-org/llama.cpp.git "$SRC"
fi

for abi in $ABIS; do
  echo "==> building $abi (minSdk $MIN_SDK)"
  build="$BUILD_ROOT/llama-$abi"
  rm -rf "$build" "$OUT/$abi"
  mkdir -p "$build"
  "$CMAKE" -S "$ROOT/app/src/main/cpp" -B "$build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$MIN_SDK" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DLLAMA_CPP_DIR="$SRC" > "$build.configure.log" 2>&1 || { tail -30 "$build.configure.log"; exit 1; }
  cmake --build "$build" --target hushyar_llama -j"$(nproc)" > "$build.build.log" 2>&1 \
    || { echo "build failed; see $build.build.log" >&2; grep -iE "error:|FAILED" "$build.build.log" | head -20 >&2; exit 1; }
  mkdir -p "$OUT/$abi"
  cp "$build/libhushyar_llama.so" "$OUT/$abi/"
  "$NDK/toolchains/llvm/prebuilt/"*/bin/llvm-strip --strip-unneeded "$OUT/$abi/libhushyar_llama.so" || true
  ls -l "$OUT/$abi/libhushyar_llama.so"
done

echo "==> done. Rebuild the app: ./gradlew assembleDebug"
