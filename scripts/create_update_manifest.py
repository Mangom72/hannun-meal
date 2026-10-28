#!/usr/bin/env python3
"""Verify a signed APK and generate the authenticated GitHub Releases update protocol."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def properties(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line and not line.startswith('#'))


def release_notes(changelog, version):
    heading=re.search(r'^# (?:한눈급식 )?'+re.escape(version)+r'\s*$',changelog,re.M)
    if not heading:
        raise ValueError('The current version needs its own changelog entry.')
    rest=changelog[heading.end():]
    next_heading=re.search(r'^# ',rest,re.M)
    return rest[:next_heading.start() if next_heading else len(rest)].strip()[:8000]


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--apk', type=Path, default=ROOT / 'app/build/outputs/apk/release/app-release.apk')
    p.add_argument('--output', type=Path, default=ROOT / 'artifacts/release')
    args = p.parse_args()
    versions = properties(ROOT / 'version.properties')
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    if not sdk and (ROOT / 'local.properties').exists():
        sdk = properties(ROOT / 'local.properties').get('sdk.dir')
    if not sdk:
        raise SystemExit('Configure ANDROID_SDK_ROOT first.')
    tools = Path(sdk) / 'build-tools/35.0.0'
    verification = subprocess.run([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(args.apk)], check=True, capture_output=True, text=True).stdout
    certificate = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})', verification)
    if not certificate or 'Number of signers: 1' not in verification:
        raise SystemExit('Exactly one verified APK signer is required.')
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(args.apk)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    minimum = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    if not package or not minimum or package.group(1) != 'kr.han.meal' or int(package.group(2)) != int(versions['VERSION_CODE']) or package.group(3) != versions['VERSION_NAME']:
        raise SystemExit('APK identity/version does not match version.properties.')
    if not re.fullmatch(r'\d+\.\d+\.\d+', versions['VERSION_NAME']):
        raise SystemExit('Use a stable numeric major.minor.patch version.')
    size = args.apk.stat().st_size
    if not 0 < size <= 80 * 1024 * 1024:
        raise SystemExit('APK exceeds the updater size bound.')
    notes = release_notes((ROOT / 'CHANGELOG.md').read_text(), versions['VERSION_NAME'])
    manifest = dict(schemaVersion=1, applicationId=package.group(1), versionCode=int(package.group(2)),
                    versionName=package.group(3), minSdk=int(minimum.group(1)), apkAssetName='hannun-meal.apk', size=size,
                    sha256=hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                    certificateSha256=certificate.group(1).lower(), notes=notes)
    args.output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.apk, args.output / 'hannun-meal.apk')
    (args.output / 'update.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    (args.output / 'release-notes.txt').write_text(notes)
    print('Verified signed APK; wrote hannun-meal.apk, update.json and release notes.')


if __name__ == '__main__':
    main()
