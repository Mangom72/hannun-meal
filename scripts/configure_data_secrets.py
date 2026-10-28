#!/usr/bin/env python3
"""Send the SAME issued key to Workers and Actions using stdin, never command arguments."""
import argparse
import getpass
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def configure(repo, key, run=subprocess.run):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo) or not key.strip():
        raise ValueError('A repository and one issued NEIS key are required.')
    commands = [
        (['gh','secret','set','NEIS_API_KEY','--repo',repo], ROOT),
        ([str(ROOT/'backend/node_modules/.bin/wrangler'),'secret','put','NEIS_API_KEY'], ROOT/'backend'),
    ]
    for index, (command, cwd) in enumerate(commands):
        result = run(command, input=key.strip().encode(), cwd=cwd, capture_output=True)
        if result.returncode:
            raise RuntimeError('Actions secret update failed.' if index == 0 else 'Actions was updated, but Worker secret update failed. Retry with the SAME key after checking Cloudflare access.')


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--repo',required=True)
    args=p.parse_args()
    key=getpass.getpass('Issued NEIS API key (hidden): ')
    try:
        configure(args.repo,key)
    except Exception as e:
        # Do not echo subprocess output; it could contain third-party credential diagnostics.
        print(str(e) if isinstance(e,(ValueError,RuntimeError)) else 'Secret configuration failed. Check local CLI authentication.')
        return 1
    print('The same issued NEIS key was sent to Actions and Worker Secrets. No key was logged or written to source.')
    return 0


if __name__=='__main__':
    raise SystemExit(main())
