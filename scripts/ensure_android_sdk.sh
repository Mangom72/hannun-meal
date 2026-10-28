#!/usr/bin/env bash
set -euo pipefail

hannun_sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$hannun_sdk_root" ]]; then
    echo 'Configure ANDROID_HOME or ANDROID_SDK_ROOT before building.' >&2
    exit 1
fi
if [[ -n "${GITHUB_ENV:-}" ]]; then
    echo "ANDROID_HOME=$hannun_sdk_root" >> "$GITHUB_ENV"
    echo "ANDROID_SDK_ROOT=$hannun_sdk_root" >> "$GITHUB_ENV"
fi
if [[ -f "$hannun_sdk_root/platforms/android-36/android.jar" && -x "$hannun_sdk_root/build-tools/35.0.0/apksigner" ]]; then
    echo 'Using installed Android 36 and Build Tools 35.0.0.'
    exit 0
fi
hannun_sdk_manager="$hannun_sdk_root/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$hannun_sdk_manager" ]]; then
    for hannun_candidate in "$hannun_sdk_root"/cmdline-tools/*/bin/sdkmanager; do
        if [[ -x "$hannun_candidate" ]]; then hannun_sdk_manager="$hannun_candidate"; break; fi
    done
fi
if [[ ! -x "$hannun_sdk_manager" ]]; then
    echo 'Android command-line tools are missing from the configured SDK.' >&2
    exit 1
fi
"$hannun_sdk_manager" --sdk_root="$hannun_sdk_root" 'platforms;android-36' 'build-tools;35.0.0'
