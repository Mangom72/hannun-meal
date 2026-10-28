#!/usr/bin/env python3
"""Explicit bootstrap for a user-authorized GitHub repository.
--push is the only flag that commits/uploads source. Never stages keys, APKs, or local configuration.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parent.parent
SOURCE=['.gitignore','AGENTS.md','README.md','CHANGELOG.md','build.gradle.kts','settings.gradle.kts','gradle.properties','version.properties','gradlew','gradlew.bat','gradle','app/build.gradle.kts','app/src','app/schemas','scripts','.github','docs','backend','pages','backend.properties.example']


def call(args,check=True):
    result=subprocess.run(args,cwd=ROOT,capture_output=True,text=True)
    if check and result.returncode:
        raise SystemExit(f'{args[0]} operation failed. Check authentication and repository permissions. No credentials were printed.')
    return result


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--repo',required=True)
    p.add_argument('--create',action='store_true')
    p.add_argument('--allow-public',action='store_true',help='Explicitly allow a user-authorized public repository')
    p.add_argument('--upload-secrets',action='store_true')
    p.add_argument('--push',action='store_true',help='Explicitly authorize a source commit and push to main')
    p.add_argument('--release',action='store_true',help='Dispatch signed release after configuration')
    a=p.parse_args()
    if len(a.repo.split('/'))!=2:raise SystemExit('Use owner/name.')
    metadata=call(['gh','api','repos/'+a.repo],check=False)
    if metadata.returncode:
        if not a.create:raise SystemExit('Create the private repository and grant this GitHub connection access first.')
        call(['gh','repo','create',a.repo,'--private','--description','한눈급식 Android · signed private releases'])
        metadata=call(['gh','api','repos/'+a.repo])
    if not json.loads(metadata.stdout)['private'] and not a.allow_public:raise SystemExit('Public repositories require the explicit --allow-public option.')
    if a.upload_secrets:
        result=subprocess.run([sys.executable,str(ROOT/'scripts/setup_signing.py'),'--upload','--repo',a.repo]+(['--allow-public'] if a.allow_public else []),cwd=ROOT)
        if result.returncode:raise SystemExit(result.returncode)
    if a.push:
        if not (ROOT/'.git').exists():call(['git','init','-b','main'])
        if call(['git','branch','--show-current']).stdout.strip()!='main':raise SystemExit('Switch to main yourself before uploading. No branches were changed.')
        if call(['git','diff','--cached','--quiet'],check=False).returncode:raise SystemExit('Existing staged changes found. Review them before running --push.')
        origin=call(['git','remote','get-url','origin'],check=False)
        expected=['https://github.com/'+a.repo+'.git','git@github.com:'+a.repo+'.git']
        if origin.returncode:call(['git','remote','add','origin',expected[0]])
        elif origin.stdout.strip() not in expected:raise SystemExit('Existing origin differs; refusing to overwrite it.')
        user=json.loads(call(['gh','api','user']).stdout)
        if call(['git','config','user.name'],check=False).returncode:call(['git','config','user.name',user['login']])
        if call(['git','config','user.email'],check=False).returncode:call(['git','config','user.email',str(user['id'])+'+'+user['login']+'@users.noreply.github.com'])
        call(['git','add','--',*SOURCE])
        staged=call(['git','diff','--cached','--name-only']).stdout.splitlines()
        if any(path.startswith(('.signing/','.tools/','artifacts/')) or path.endswith(('.p12','.jks','.keystore')) for path in staged):raise SystemExit('Refusing to commit signing material or generated artifacts.')
        if staged:call(['git','commit','-m','Publish Hannun Meal with secure public updates'])
        call(['git','-c','credential.helper=','-c','credential.helper=!gh auth git-credential','push','-u','origin','main'])
        print('Uploaded reviewed source to repository main.')
    if a.release:
        call(['gh','workflow','run','release.yml','--repo',a.repo,'--ref','main'])
        print('Dispatched signed release workflow. Inspect GitHub Actions for its outcome.')
    print('Repository:',a.repo)


if __name__=='__main__':main()
