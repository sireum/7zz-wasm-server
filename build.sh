#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

P7ZIP_V=26.00
WASI_SDK="${WASI_SDK:-$HOME/wasi-sdk}"
NPROC=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)

if [ ! -x "$WASI_SDK/bin/clang" ]; then
  echo "ERROR: wasi-sdk not found at $WASI_SDK"
  echo "Set WASI_SDK env var or install to ~/wasi-sdk"
  exit 1
fi

echo "=== Step 1: Clone and patch 7-zip ==="
if [ ! -d 7zip ]; then
  git clone https://github.com/ip7z/7zip
fi
cd 7zip
git checkout "$P7ZIP_V" 2>/dev/null || true
git checkout -- .

# Apply patches (git apply fails loudly if hunks don't match)
for p in "$SCRIPT_DIR"/patches/*.patch; do
  echo "  Applying $(basename "$p") ..."
  git apply "$p"
done

# Copy WASI compat header into source tree
cp "$SCRIPT_DIR/wasi_compat.h" CPP/Common/

# Replace MainAr.cpp with server_main.cpp
cp "$SCRIPT_DIR/server_main.cpp" CPP/7zip/UI/Console/MainAr.cpp

cd "$SCRIPT_DIR"

echo "=== Step 2: Compile with wasi-sdk (single-threaded, WASI) ==="
cd 7zip/CPP/7zip/Bundles/Alone2

# Clean previous build
rm -rf _o_wasm
mkdir -p _o_wasm

CC="$WASI_SDK/bin/clang"
CXX="$WASI_SDK/bin/clang++"
WASI_CFLAGS="-O2 -c -DNDEBUG -D_FILE_OFFSET_BITS=64 -D_LARGEFILE_SOURCE -fexceptions"
WASI_CFLAGS="$WASI_CFLAGS -D_WASI_EMULATED_SIGNAL -D_WASI_EMULATED_GETPID -D_WASI_EMULATED_PROCESS_CLOCKS"
WASI_CFLAGS="$WASI_CFLAGS -include wasi_compat.h -I$SCRIPT_DIR/wasi_include -I../../../../CPP/Common"
WASI_LDFLAGS="-Wl,--wrap=chmod -Wl,--wrap=fchmod -Wl,--wrap=fchmodat"
WASI_LDLIBS="_o_wasm/wasi_cxa_stubs.o -lsetjmp -lwasi-emulated-signal -lwasi-emulated-getpid -lwasi-emulated-process-clocks"

# Compile C++ exception stubs
"$CXX" -O2 -c -fexceptions "$SCRIPT_DIR/wasi_cxa_stubs.cpp" -o _o_wasm/wasi_cxa_stubs.o

make -j"$NPROC" -f makefile.gcc \
  CC="$CC" CXX="$CXX" \
  O=_o_wasm \
  SHARED_EXT=.wasm \
  ST_MODE=1 \
  CFLAGS_WARN_WALL="-Wall -Wextra" \
  CFLAGS_BASE="$WASI_CFLAGS" \
  FLAGS_FLTO="" \
  FLAGS_BASE="" \
  LIB2="$WASI_LDLIBS" \
  LDFLAGS="$WASI_LDFLAGS" \
  LFLAGS_STRIP="" \
  LFLAGS_NOEXECSTACK=""

cd "$SCRIPT_DIR"
cp 7zip/CPP/7zip/Bundles/Alone2/_o_wasm/7zz.wasm 7zz_server.wasm

echo "=== Step 3: GraalWasm variant ==="
if command -v wasm-opt &>/dev/null; then
  wasm-opt --all-features -O2 \
    7zz_server.wasm -o 7zz_server_graal.wasm
  echo "=== Build complete ==="
  ls -lh 7zz_server.wasm 7zz_server_graal.wasm
else
  echo "wasm-opt not found, skipping GraalWasm optimization"
  cp 7zz_server.wasm 7zz_server_graal.wasm
  echo "=== Build complete ==="
  ls -lh 7zz_server.wasm 7zz_server_graal.wasm
fi
