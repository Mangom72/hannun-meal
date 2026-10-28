#!/usr/bin/env python3
"""Publish only the reviewed directory generator/automation; never the Android or Worker source."""
import argparse,base64,json,re,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
REPO='Mangom72/hannun-meal-schools'
MARKER='<!-- hannun-meal-directory-publisher v1 -->'
FILES=['scripts/sync_school_directory.py','scripts/check_client_security.py','scripts/tests/test_school_directory.py','scripts/tests/test_client_security.py','pages/.nojekyll','pages/index.html']
def package():
    files={name:(ROOT/name).read_text() for name in FILES}
    workflow=(ROOT/'.github/workflows/school-directory.yml').read_text()
    workflow=workflow.replace("    if: ${{ vars.SCHOOL_DIRECTORY_EXTERNAL != 'true' }}\n",'')
    workflow=workflow.replace('  build:\n','  build:\n    if: ${{ vars.DIRECTORY_READY == \'true\' }}\n',1)
    files['.github/workflows/school-directory.yml']=workflow
    files['README.md']=MARKER+'\n# 한눈급식 전국 학교 목록\n\n공식 NEIS 학교기본정보에서 공개 초·중·고 학교 목록만 생성해 Pages로 배포합니다. Android 앱 소스, Worker 소스, APK, 서명키는 이 저장소에 포함하지 않습니다.\n\nNEIS_API_KEY는 Actions Secret으로만 설정합니다. DIRECTORY_READY=true 설정 후 주간/수동 workflow를 실행합니다. 생성 실패 시 기존 배포를 유지합니다. 초기 인증키 준비 전에는 배포하지 않으며 예시 학교 데이터를 게시하지 않습니다.\n'
    files['.gitignore']='__pycache__/\n*.pyc\npages/schools*.json\n'
    return files

def api(path,method='GET',data=None,optional=False):
    args=['gh','api',path]
    if method!='GET':args+=['--method',method]
    if data is not None:args+=['--input','-']
    p=subprocess.run(args,input=None if data is None else json.dumps(data),capture_output=True,text=True)
    if p.returncode:
        if optional:return None
        raise RuntimeError('GitHub setup operation failed; no credential diagnostics were printed.')
    return json.loads(p.stdout) if p.stdout.strip() else {}

def publish(repo,files):
    if repo!=REPO:raise ValueError('This reviewed package is restricted to the dedicated school-data repository.')
    meta=api('repos/'+repo)
    if meta.get('private') or not meta.get('permissions',{}).get('admin'):raise ValueError('An owned public school-data repository with admin access is required.')
    readme=api('repos/'+repo+'/contents/README.md',optional=True)
    if readme is None:
        contents=api('repos/'+repo+'/contents',optional=True)
        if contents:raise ValueError('Unexpected existing repository content; refusing to overwrite it.')
        api('repos/'+repo+'/contents/README.md','PUT',{'message':'Initialize public school-directory publisher','content':base64.b64encode(files['README.md'].encode()).decode(),'branch':'main'})
    elif MARKER not in base64.b64decode(readme['content']).decode():raise ValueError('Repository marker missing; refusing to overwrite user content.')
    ref=api('repos/'+repo+'/git/ref/heads/main');head=ref['object']['sha']
    commit=api('repos/'+repo+'/git/commits/'+head)
    tree=api('repos/'+repo+'/git/trees/'+commit['tree']['sha']+'?recursive=1')
    existing={x['path'] for x in tree['tree'] if x['type']=='blob'}
    if existing-set(files):raise ValueError('Unexpected remote files; refusing to overwrite them.')
    newtree=api('repos/'+repo+'/git/trees','POST',{'base_tree':commit['tree']['sha'],'tree':[{'path':k,'mode':'100644','type':'blob','content':v} for k,v in files.items()]})
    newcommit=api('repos/'+repo+'/git/commits','POST',{'message':'Add validated nationwide school-directory automation','tree':newtree['sha'],'parents':[head]})
    api('repos/'+repo+'/git/refs/heads/main','PATCH',{'sha':newcommit['sha'],'force':False})
    pages=api('repos/'+repo+'/pages',optional=True)
    if pages is None:pages=api('repos/'+repo+'/pages','POST',{'build_type':'workflow'})
    return {'repository':meta['html_url'],'pages':pages['html_url'],'commit':newcommit['sha'],'publishedFiles':sorted(files),'dataDeployed':False}

def main():
    p=argparse.ArgumentParser();p.add_argument('--repo',default=REPO);p.add_argument('--publish',action='store_true');a=p.parse_args()
    files=package();out=ROOT/'artifacts/data-setup-20261028/public-repo'
    for name,value in files.items():q=out/name;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(value)
    if a.publish:
        result=publish(a.repo,files);(out.parent/'repository.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(result,ensure_ascii=False))
    else:print('Reviewed public-data package prepared:',out)
if __name__=='__main__':main()
