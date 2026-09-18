#!/usr/bin/env bash
set -euo pipefail

APK_PATH="${1:-}"
NDK_VERSION="${2:-30.0.16248370}"

if [[ -z "$APK_PATH" || ! -f "$APK_PATH" ]]; then
  echo "Usage: $0 <apk> [ndk-version]" >&2
  exit 2
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-$SDK_ROOT/ndk/$NDK_VERSION}}"
LLVM_OBJDUMP="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump"
ZIPALIGN=""
if [[ -x "${SDK_ROOT}/build-tools/36.0.0/zipalign" ]]; then
  ZIPALIGN="${SDK_ROOT}/build-tools/36.0.0/zipalign"
else
  ZIPALIGN="$(find "${SDK_ROOT}/build-tools" -mindepth 2 -maxdepth 2 -type f -name zipalign 2>/dev/null | sort -V | tail -n 1)"
fi

if [[ ! -x "$LLVM_OBJDUMP" ]]; then
  echo "ERROR: llvm-objdump not found: $LLVM_OBJDUMP" >&2
  exit 1
fi

if [[ ! -x "$ZIPALIGN" ]]; then
  echo "ERROR: zipalign not found: $ZIPALIGN" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

unzip -q -o "$APK_PATH" 'lib/*/*.so' -d "$TMP_DIR"

mapfile -t SO_FILES < <(find "$TMP_DIR/lib" -type f -name '*.so' | sort)
if (( ${#SO_FILES[@]} == 0 )); then
  echo "ERROR: no native shared libraries found in APK" >&2
  exit 1
fi

failures=0
for so in "${SO_FILES[@]}"; do
  rel="${so#"$TMP_DIR/"}"
  mapfile -t aligns < <("$LLVM_OBJDUMP" -p "$so" | awk '$1 == "LOAD" { print $NF }')

  if (( ${#aligns[@]} == 0 )); then
    echo "ERROR: $rel has no PT_LOAD entries" >&2
    failures=$((failures + 1))
    continue
  fi

  for align in "${aligns[@]}"; do
    align_dec=0
    if [[ "$align" =~ ^2\*\*([0-9]+)$ ]]; then
      exponent="${BASH_REMATCH[1]}"
      align_dec=$((1 << exponent))
    elif [[ "$align" =~ ^0x[0-9a-fA-F]+$ ]]; then
      align_dec=$((align))
    elif [[ "$align" =~ ^[0-9]+$ ]]; then
      align_dec=$((align))
    else
      echo "ERROR: $rel has unrecognized LOAD alignment: $align" >&2
      failures=$((failures + 1))
      continue
    fi

    if (( align_dec < 16384 )); then
      echo "ERROR: $rel has LOAD alignment $align_dec bytes (< 16384)" >&2
      failures=$((failures + 1))
    fi
  done

  echo "OK: $rel LOAD alignment(s): ${aligns[*]}"
done

if (( failures != 0 )); then
  exit 1
fi


compressed_libs=0
while read -r method path; do
  [[ "$path" == lib/*/*.so ]] || continue
  if [[ "$method" != "Stored" ]]; then
    echo "ERROR: $path is compressed in APK (method=$method)" >&2
    compressed_libs=$((compressed_libs + 1))
  fi
done < <(unzip -lv "$APK_PATH" | awk '$1 ~ /^[0-9]+$/ && $9 ~ /^lib\/.+\.so$/ {print $2, $9}')

if (( compressed_libs != 0 )); then
  exit 1
fi

"$ZIPALIGN" -c -P 16 -v 4 "$APK_PATH"

echo "16 KB ELF and APK zip alignment verification passed for all native libraries in $APK_PATH"