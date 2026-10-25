#!/bin/sh
# Builds voprf/ into src/voprf.wasm, the relay's half of the blind send tokens.
# The .wasm is committed so the Worker deploys without a Rust toolchain; CI
# rebuilds it with the pinned compiler and fails if the bytes differ, so what
# is deployed is what this source makes.
#   sh scripts/build-voprf.sh          rebuild src/voprf.wasm
#   sh scripts/build-voprf.sh --check  rebuild and compare with the committed file
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
cd "$here/voprf"
# Paths the compiler would otherwise write into the binary are mapped to fixed names.
export RUSTFLAGS="--remap-path-prefix=$here/voprf=/voprf --remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}=/cargo"
cargo build --locked --release --target wasm32-unknown-unknown
built="$here/voprf/target/wasm32-unknown-unknown/release/clairix_relay_voprf.wasm"
if [ "${1:-}" = "--check" ]; then
  if cmp -s "$built" "$here/src/voprf.wasm"; then
    echo "src/voprf.wasm matches its source ($(sha256sum "$built" | cut -d' ' -f1))"
  else
    echo "src/voprf.wasm does not match what voprf/ builds: run sh scripts/build-voprf.sh and commit it" >&2
    sha256sum "$built" "$here/src/voprf.wasm" >&2
    exit 1
  fi
else
  cp "$built" "$here/src/voprf.wasm"
  echo "wrote src/voprf.wasm ($(wc -c < "$here/src/voprf.wasm") bytes, $(sha256sum "$here/src/voprf.wasm" | cut -d' ' -f1))"
fi
