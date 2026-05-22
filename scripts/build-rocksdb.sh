#!/usr/bin/env bash
# Build RocksDB shared library using zig cc/c++ and install it into the
# caller's resources directory so Maven bundles it in the JAR.
#
# Supports cross-compilation: runs on any host but can produce a binary
# for any supported target by passing a TARGET_CLASSIFIER.
#
# Compression support:
#   Native builds (CLASSIFIER == host) on macOS: snappy, lz4, and zstd are
#   detected from the homebrew prefix and statically linked into librocksdb.dylib
#   so the resulting JAR is self-contained (no homebrew runtime dependency).
#   Cross-compiled builds: compression disabled (no cross-sysroot available).
#
# Usage:
#   ./scripts/build-rocksdb.sh <output-resources-dir> <target-classifier>
#
# target-classifier: osx-aarch64 | osx-x86_64 | linux-x86_64 | linux-aarch64
#
# Example (Maven exec plugin):
#   ./scripts/build-rocksdb.sh /path/to/native/osx-aarch64/src/main/resources osx-aarch64
set -euo pipefail

if [ $# -lt 2 ]; then
    echo "Usage: $0 <output-resources-dir> <target-classifier>" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"   # multi-module root
ROCKSDB_DIR="$PROJECT_DIR/rocksdb"
# Resolve to absolute path before any cd changes the working directory
mkdir -p "$1"
OUTPUT_RESOURCES="$(cd "$1" && pwd)"
CLASSIFIER="$2"
JOBS="${ROCKSDB_BUILD_JOBS:-$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)}"

# ---------------------------------------------------------------------------
# Map classifier → (zig target triple, library name, RocksDB platform)
# ---------------------------------------------------------------------------
case "$CLASSIFIER" in
    osx-aarch64)
        ZIG_TARGET="aarch64-macos"
        LIB_NAME="librocksdb.dylib"
        TARGET_OS="Darwin"
        ;;
    osx-x86_64)
        ZIG_TARGET="x86_64-macos"
        LIB_NAME="librocksdb.dylib"
        TARGET_OS="Darwin"
        ;;
    linux-x86_64)
        ZIG_TARGET="x86_64-linux-gnu"
        LIB_NAME="librocksdb.so"
        TARGET_OS="Linux"
        ;;
    linux-aarch64)
        ZIG_TARGET="aarch64-linux-gnu"
        LIB_NAME="librocksdb.so"
        TARGET_OS="Linux"
        ;;
    *)
        echo "Unsupported classifier: $CLASSIFIER" >&2
        exit 1
        ;;
esac

DEST_DIR="$OUTPUT_RESOURCES/native/$CLASSIFIER"
mkdir -p "$DEST_DIR"

# Skip if already built (CI cache or repeated local builds)
if [ -f "$DEST_DIR/$LIB_NAME" ]; then
    echo "[build-rocksdb] $DEST_DIR/$LIB_NAME already exists, skipping build."
    exit 0
fi

# ---------------------------------------------------------------------------
# Detect whether we are cross-compiling
# ---------------------------------------------------------------------------
HOST_OS=$(uname -s)
HOST_ARCH=$(uname -m)
case "$HOST_OS" in Darwin) HOST_OS_NAME="osx"   ;; Linux) HOST_OS_NAME="linux" ;; esac
case "$HOST_ARCH" in arm64|aarch64) HOST_ARCH_NAME="aarch64" ;; x86_64) HOST_ARCH_NAME="x86_64" ;; esac
HOST_CLASSIFIER="${HOST_OS_NAME}-${HOST_ARCH_NAME}"

CROSS=""
if [ "$CLASSIFIER" != "$HOST_CLASSIFIER" ]; then
    CROSS=" (cross from $HOST_CLASSIFIER)"
fi

# ---------------------------------------------------------------------------
# Compression codec setup
#
# Native macOS builds: inject the homebrew include path into CC/CXX so that
# build_detect_platform's header probes succeed. The matching -L path is
# passed via EXTRA_LDFLAGS so zig resolves -lsnappy/-llz4/-lzstd at link time.
#
# Native Linux builds: use pkg-config to locate include and lib paths.
# Falls back to probing /usr/include and /usr/local/include directly if
# pkg-config is absent or the packages are not registered with it.
#
# Cross-compiled builds: compression disabled (no cross-sysroot available).
# ---------------------------------------------------------------------------
PATCH_COMPRESSION=0
COMP_CFLAGS=""
COMPRESSION_LDFLAGS=""

if [ "$CLASSIFIER" = "$HOST_CLASSIFIER" ] && [ "$TARGET_OS" = "Darwin" ]; then
    HOMEBREW_PREFIX="$(brew --prefix 2>/dev/null || echo /opt/homebrew)"
    if [ -d "${HOMEBREW_PREFIX}/include" ]; then
        PATCH_COMPRESSION=1
        COMP_CFLAGS="-I${HOMEBREW_PREFIX}/include"
        COMPRESSION_LDFLAGS="-L${HOMEBREW_PREFIX}/lib"
        echo "[build-rocksdb] Native macOS build — snappy/lz4/zstd from $HOMEBREW_PREFIX"
    fi

elif [ "$CLASSIFIER" = "$HOST_CLASSIFIER" ] && [ "$TARGET_OS" = "Linux" ]; then
    LINUX_INCLUDES=""
    LINUX_LIBDIRS=""
    # Resolve early: needed for both header probe and lib dir probe below.
    ARCH_TRIPLET="$(gcc -dumpmachine 2>/dev/null || echo ${HOST_ARCH_NAME}-linux-gnu)"

    # Prefer pkg-config; it handles multi-arch lib paths transparently.
    if command -v pkg-config >/dev/null 2>&1; then
        for pkg in liblz4 snappy libzstd; do
            if pkg-config --exists "$pkg" 2>/dev/null; then
                LINUX_INCLUDES="$LINUX_INCLUDES $(pkg-config --cflags-only-I "$pkg" 2>/dev/null)"
                LINUX_LIBDIRS="$LINUX_LIBDIRS $(pkg-config --libs-only-L "$pkg" 2>/dev/null)"
            fi
        done
    fi

    # Fall back: probe standard and multiarch include paths for any libs pkg-config missed
    # (or reported with no -I because they live in a default search path).
    # On Debian/Ubuntu aarch64, lz4.h lives in /usr/include/aarch64-linux-gnu, not /usr/include.
    # Use -isystem (not -I) so zig searches these AFTER its own libc++ headers;
    # -I would prepend them and break zig's <cstdlib> → <stdlib.h> resolution.
    for dir in /usr/include "/usr/include/${ARCH_TRIPLET}" /usr/local/include; do
        if [ -f "$dir/lz4.h" ] || [ -f "$dir/snappy.h" ] || [ -f "$dir/zstd.h" ]; then
            LINUX_INCLUDES="$LINUX_INCLUDES -isystem $dir"
        fi
    done

    # Add common lib search paths so the linker finds the .so/.a files.
    for dir in "/usr/lib/${ARCH_TRIPLET}" /usr/lib /usr/local/lib; do
        [ -d "$dir" ] && LINUX_LIBDIRS="$LINUX_LIBDIRS -L$dir"
    done

    if [ -n "$LINUX_INCLUDES" ]; then
        PATCH_COMPRESSION=1
        # Deduplicate and trim whitespace
        COMP_CFLAGS="$(echo "$LINUX_INCLUDES" | tr ' ' '\n' | sort -u | tr '\n' ' ' | xargs)"
        COMPRESSION_LDFLAGS="$(echo "$LINUX_LIBDIRS" | tr ' ' '\n' | sort -u | tr '\n' ' ' | xargs)"
        echo "[build-rocksdb] Native Linux build — snappy/lz4/zstd: includes='$COMP_CFLAGS' libs='$COMPRESSION_LDFLAGS'"
    fi
fi

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
echo "[build-rocksdb] Building RocksDB $CLASSIFIER$CROSS with zig cc/c++ (jobs=$JOBS)..."

if [ "$PATCH_COMPRESSION" = "1" ]; then
    # Bake the platform-specific include paths into the compiler so that
    # build_detect_platform's header probes succeed for snappy/lz4/zstd.
    # The matching -L paths are passed via EXTRA_LDFLAGS at link time.
    export CC="zig cc -target $ZIG_TARGET $COMP_CFLAGS"
    export CXX="zig c++ -target $ZIG_TARGET $COMP_CFLAGS"
else
    export CC="zig cc -target $ZIG_TARGET"
    export CXX="zig c++ -target $ZIG_TARGET"
    export ROCKSDB_DISABLE_SNAPPY=1
fi

export PORTABLE=1
export ROCKSDB_DISABLE_BZ2=1
export ROCKSDB_DISABLE_ZLIB=1
# gflags is not needed in the shared lib; skip it to avoid an extra runtime dep.
export ROCKSDB_DISABLE_GFLAGS=1
export TARGET_OS=$TARGET_OS
cd "$ROCKSDB_DIR"

# zig cc/c++ treats some warnings as errors that RocksDB's own build does not
# expect (e.g. -Wunused-parameter in util/compression.cc). Suppress them for
# all builds so the Makefile does not abort on RocksDB's own code.
EXTRA_FLAGS="-Wno-error"

# Cross-compilation: existing .o files and make_config.mk are for the host
# architecture. Remove them so RocksDB's build_detect_platform regenerates
# the config and Make recompiles everything with the cross target.
rm -f make_config.mk
make clean -j"$JOBS" 2>/dev/null || true

make shared_lib \
    DEBUG_LEVEL=0 \
    EXTRA_LDFLAGS="-s ${COMPRESSION_LDFLAGS}" \
    EXTRA_CXXFLAGS="$EXTRA_FLAGS" \
    EXTRA_CFLAGS="$EXTRA_FLAGS" \
    -j"$JOBS"

# ---------------------------------------------------------------------------
# Install
# ---------------------------------------------------------------------------
cp "$ROCKSDB_DIR/$LIB_NAME" "$DEST_DIR/$LIB_NAME"
echo "[build-rocksdb] Installed: $DEST_DIR/$LIB_NAME"
