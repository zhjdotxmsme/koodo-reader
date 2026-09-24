#!/usr/bin/env bash
# check-elf-16kb.sh — verify every .so in an APK is 16KB-page-aligned (P3).
#
# Google Play HARD-requires 16KB page alignment on Android 15+ devices
# (Play targetSdk=35+; the requirement ships with the OS for all new
# uploads). pdf.js ships in our APK as pure JS, so the WebView engine
# path is exempt; this script guards every OTHER .so (NDK libraries,
# prebuilt third-party binaries).
#
# How to read an APK's alignment:
#   1) unzip -l app.apk | grep '\.so$' | awk '{print $NF}'
#   2) for each .so, check the LOAD segments against llvm-readelf:
#        llvm-readelf -l libfoo.so | grep -E '^\s+LOAD'
#      Every LOAD must have p_align % 0x4000 == 0 (i.e. 16384).
#
# Usage:
#   bash scripts/check-elf-16kb.sh <app.apk>
#   bash scripts/check-elf-16kb.sh --no-apk <apk_unpacked_dir>
#
# Exits non-zero when ANY .so fails the check.
set -euo pipefail

# Locate llvm-readelf; NDK r26+ ships it as
#   $NDK/toolchains/llvm/prebuilt/<host>/bin/llvm-readelf
READELF="${READELF:-llvm-readelf}"
ZIPALIGN="${ZIPALIGN:-zipalign}"

usage() {
    cat <<EOF
Usage: $0 <app.apk>
       $0 --no-apk <apk_unpacked_dir>
Environment:
  READELF   path to llvm-readelf (default: llvm-readelf)
  ZIPALIGN  path to zipalign (default: zipalign)
EOF
}

if [[ $# -lt 1 ]]; then usage; exit 2; fi

if [[ "$1" == "--no-apk" ]]; then
    [[ $# -ge 2 ]] || { usage; exit 2; }
    APK_DIR="$2"
else
    APK="$1"
    [[ -f "$APK" ]] || { echo "APK not found: $APK"; exit 2; }
    APK_DIR="$(mktemp -d -t pdf16kb.XXXXXX)"
    trap 'rm -rf "$APK_DIR"' EXIT
    unzip -q "$APK" -d "$APK_DIR"
fi

# A readelf-friendly fallback is bundled with most NDKs; when neither
# is present, fail with a clear instruction instead of silently passing.
if ! command -v "$READELF" >/dev/null 2>&1; then
    echo "ERROR: '$READELF' not found. Install Android NDK r26+ and set READELF=<path>" >&2
    echo "       (e.g. \$ANDROID_HOME/ndk/26.0.10709118/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf)" >&2
    exit 3
fi

# Find every .so under lib/ (or anywhere — the script is conservative).
mapfile -t SOS < <(find "$APK_DIR" -name '*.so' -type f | sort)

if [[ ${#SOS[@]} -eq 0 ]]; then
    echo "INFO: no .so files in APK — no 16KB check required"
    exit 0
fi

failed=0
total=${#SOS[@]}
echo "Checking $total .so file(s) for 16KB page alignment..."

for so in "${SOS[@]}"; do
    rel="${so#$APK_DIR/}"
    if ! "$READELF" -l "$so" 2>/dev/null | awk '/^\s*LOAD/ { if (strtonum("0x" $7) % 0x4000 != 0) exit 1 }' >/dev/null 2>&1; then
        if "$READELF" -l "$so" 2>/dev/null | awk '/^\s*LOAD/ { if (strtonum("0x" $7) % 0x4000 != 0) exit 1 }' >/dev/null; then
            echo "  FAIL  $rel  (LOAD alignment < 16KB)"
            failed=$((failed + 1))
        else
            echo "  PASS  $rel"
        fi
    else
        echo "  FAIL  $rel  (readelf parse failed)"
        failed=$((failed + 1))
    fi
done

if [[ $failed -gt 0 ]]; then
    echo
    echo "$failed of $total .so file(s) failed 16KB alignment"
    exit 1
fi

echo
echo "All $total .so file(s) are 16KB-page-aligned."

# Optional: zipalign sanity (page-alignment of .zip entries). This is a
# Play upload requirement, independent of the .so check, but the script
# stays useful in CI by running it when ZIPALIGN is on PATH.
if [[ -n "${ZIPALIGN:-}" ]] && command -v "$ZIPALIGN" >/dev/null 2>&1 && [[ -n "${APK:-}" ]]; then
    if "$ZIPALIGN" -c -v 4 "$APK" >/dev/null 2>&1; then
        echo "zipalign: OK"
    else
        echo "WARN: zipalign -c 4 failed; the APK is not Play-upload-ready" >&2
    fi
fi