#!/usr/bin/env python3
"""Advance stable release name and monotonic versionCode without committing or pushing."""
import argparse
from pathlib import Path
import re


def bump(path:Path,version:str):
    if not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)',version):
        raise ValueError('Use major.minor.patch without leading zeros.')
    lines=path.read_text().splitlines()
    values=dict(line.split('=',1) for line in lines if '=' in line)
    previous=tuple(map(int,values['VERSION_NAME'].split('.')))
    if tuple(map(int,version.split('.')))<=previous:
        raise ValueError('The release version must increase.')
    code=int(values['VERSION_CODE'])+1
    if code>2_147_483_647:
        raise ValueError('Android versionCode exhausted.')
    replacements={'VERSION_NAME':version,'VERSION_CODE':str(code)}
    path.write_text('\n'.join(f'{line.split("=",1)[0]}={replacements[line.split("=",1)[0]]}' if '=' in line and line.split('=',1)[0] in replacements else line for line in lines)+'\n')


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('version');a=p.parse_args()
    try:bump(Path(__file__).resolve().parent.parent/'version.properties',a.version)
    except ValueError as e:raise SystemExit(str(e))
    print('Updated version.properties. Review and commit before creating the release tag.')
