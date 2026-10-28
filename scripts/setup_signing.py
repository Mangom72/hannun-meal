#!/usr/bin/env python3
"""Generate a private local release key; optionally upload it to GitHub Actions Secrets.
Never writes credentials to stdout, git, command arguments, or the APK.
"""
import argparse
import base64
import os
from pathlib import Path
import secrets
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def run(command, **kwargs):
    result = subprocess.run(command, capture_output=True, **kwargs)
    if result.returncode:
        raise SystemExit(f"Command failed: {command[0]}. Check authentication, permissions, and local tools.")
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', help='owner/repo; required with --upload')
    parser.add_argument('--allow-public',action='store_true',help='Explicitly allow Actions Secrets on a user-authorized public repository')
    parser.add_argument('--upload', action='store_true', help='Upload existing/generated key to Actions secrets')
    args = parser.parse_args()
    directory = ROOT / '.signing'
    directory.mkdir(mode=0o700, exist_ok=True)
    key = directory / 'release.p12'
    properties = directory / 'signing.properties'
    if key.exists() != properties.exists():
        raise SystemExit('Incomplete signing files. Restore the original pair; never overwrite a release key.')
    if args.upload:
        if not args.repo or len(args.repo.split('/')) != 2:
            raise SystemExit('--repo owner/name is required.')
        if run(['gh', 'api', 'repos/' + args.repo, '--jq', '.private']).stdout.strip() != b'true' and not args.allow_public:
            raise SystemExit('Public repositories require --allow-public; signing material still remains private.')
        if not key.exists():
            count=int(run(['gh','api','repos/'+args.repo+'/releases','--jq','length']).stdout.strip())
            if count:
                raise SystemExit('This repository already has releases. Restore the original signing key; generating a replacement would break updates.')
    if not key.exists():
        java_home = os.environ.get('JAVA_HOME')
        keytool = str(Path(java_home) / 'bin/keytool') if java_home else shutil.which('keytool')
        if not keytool:
            raise SystemExit('Install a JDK and configure JAVA_HOME first.')
        password = secrets.token_urlsafe(36)
        environment = dict(os.environ, HANNUN_KEYSTORE_PASSWORD=password)
        run([keytool, '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12',
             '-alias', 'hannun-release', '-keyalg', 'RSA', '-keysize', '3072', '-sigalg', 'SHA256withRSA',
             '-validity', '10000', '-dname', 'CN=Hannun Meal, OU=Android, O=Hannun',
             '-storepass:env', 'HANNUN_KEYSTORE_PASSWORD', '-keypass:env', 'HANNUN_KEYSTORE_PASSWORD'], env=environment)
        properties.write_text('storeFile=.signing/release.p12\nstorePassword=' + password +
                              '\nkeyAlias=hannun-release\nkeyPassword=' + password + '\n')
        key.chmod(0o600)
        properties.chmod(0o600)
        print('Created .signing/release.p12 and .signing/signing.properties. Back up both privately.')
    else:
        print('Preserving the existing release key.')
    if args.upload:
        if not args.repo or len(args.repo.split('/')) != 2:
            raise SystemExit('--repo owner/name is required.')
        metadata = run(['gh', 'api', 'repos/' + args.repo, '--jq', '.private']).stdout.strip()
        if metadata != b'true' and not args.allow_public:
            raise SystemExit('Public repositories require the explicit --allow-public option.')
        values = dict(line.split('=', 1) for line in properties.read_text().splitlines() if '=' in line)
        credentials = {
            'ANDROID_KEYSTORE_BASE64': base64.b64encode(key.read_bytes()),
            'ANDROID_KEYSTORE_PASSWORD': values['storePassword'].encode(),
            'ANDROID_KEY_ALIAS': values['keyAlias'].encode(),
            'ANDROID_KEY_PASSWORD': values['keyPassword'].encode(),
        }
        for name, value in credentials.items():
            run(['gh', 'secret', 'set', name, '--repo', args.repo], input=value)
        print('Uploaded four signing secrets. No private key was committed.')


if __name__ == '__main__':
    main()
