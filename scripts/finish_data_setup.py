#!/usr/bin/env python3
"""Interactive local continuation. Credentials never enter command arguments or files."""
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from zoneinfo import ZoneInfo
from urllib.parse import urlencode
from urllib.error import HTTPError, URLError

from configure_data_secrets import configure
from prepare_school_repository import api, REPO
from sync_school_directory import download, download_bytes, page, validate, PUBLIC_USER_AGENT

ROOT = Path(__file__).resolve().parents[1]
WRANGLER = ROOT / 'backend/node_modules/.bin/wrangler'
PAGES = 'https://mangom72.github.io/hannun-meal-schools/'
OUT = ROOT / 'artifacts/data-setup-20261028'
SCOPES = ['account:read', 'user:read', 'workers_scripts:write', 'workers_tail:read']


def progress(stage, code=None):
    OUT.mkdir(parents=True, exist_ok=True)
    value = {'stage':stage, 'code':code, 'updatedAt':datetime.now(ZoneInfo('Asia/Seoul')).isoformat()}
    with tempfile.NamedTemporaryFile(dir=OUT, mode='w', delete=False) as f:
        json.dump(value, f)
        temporary = f.name
    os.replace(temporary, OUT/'progress.json')


class KeyCheckFailure(Exception):
    def __init__(self, code, message, replace_key=False):
        super().__init__(message)
        self.code, self.replace_key = code, replace_key


def check_key(key):
    # Authentication is independent of nationwide completeness. Keep this probe small.
    url = 'https://open.neis.go.kr/hub/schoolInfo?' + urlencode(dict(
        KEY=key, Type='json', pIndex=1, pSize=1,
        ATPT_OFCDC_SC_CODE='B10', SD_SCHUL_CODE='7010057'))
    try:
        root = download(url)
    except HTTPError as error:
        status = error.code
        error.close()
        raise KeyCheckFailure('HTTP_'+str(status), f'NEIS 서버가 HTTP {status}을 반환했습니다. 키 오류로 판정하지 않았습니다.') from None
    except (URLError, TimeoutError, OSError):
        raise KeyCheckFailure('NETWORK', 'NEIS 연결에 실패했습니다. 네트워크/TLS 상태를 확인하십시오. 키 오류로 판정하지 않았습니다.') from None
    except (ValueError, UnicodeError):
        raise KeyCheckFailure('INVALID_RESPONSE', 'NEIS 응답이 정상 JSON이 아닙니다. 키 오류로 판정하지 않았습니다.') from None
    try:
        codes = [root['RESULT'].get('CODE')] if 'RESULT' in root else [
            h['RESULT'].get('CODE') for part in root.get('schoolInfo', [])
            for h in part.get('head', []) if 'RESULT' in h]
        if 'ERROR-290' in codes:
            raise KeyCheckFailure('ERROR-290', 'NEIS가 인증키를 거부했습니다(ERROR-290). NEIS에서 발급된 인증키 값만 다시 복사하십시오.', True)
        if any(code in {'INFO-300', 'INFO-100'} for code in codes):
            raise KeyCheckFailure('KEY_RESTRICTED', 'NEIS에서 키 사용 제한 또는 참고용 응답을 반환했습니다. 포털의 인증키 발급/사용 상태를 확인하십시오.', True)
        if 'ERROR-337' in codes:
            raise KeyCheckFailure('ERROR-337', 'NEIS 일일 호출 한도에 도달했습니다(ERROR-337). 다른 키로 우회하지 말고 한도 초기화 후 다시 시도하십시오.')
        total, rows = page(root)
        if total < 1 or not any(row.get('ATPT_OFCDC_SC_CODE') == 'B10' and row.get('SD_SCHUL_CODE') == '7010057' for row in rows):
            raise ValueError('identity')
    except KeyCheckFailure:
        raise
    except Exception:
        raise KeyCheckFailure('UNEXPECTED_RESPONSE', 'NEIS 학교 확인 응답이 예상 형식과 다릅니다. 키 오류로 판정하지 않았습니다.') from None


def quiet(command, **kwargs):
    return subprocess.run(command, capture_output=True, text=True, **kwargs)


def open_approval(url):
    # Authentication and human challenges belong in the user's ordinary browser.
    result = quiet(['xdg-open', url], timeout=20)
    if result.returncode:
        raise RuntimeError('기본 브라우저를 열지 못했습니다. 브라우저 실행 상태를 확인하십시오.')


def identity():
    result = quiet([str(WRANGLER), 'whoami', '--json'], cwd=ROOT/'backend')
    if result.returncode:
        return None
    try:
        value = json.loads(result.stdout)
        return value if value.get('loggedIn') else None
    except ValueError:
        return None


def authorize():
    user = identity()
    while user is None:
        input('\n① 평소 쓰는 브라우저의 Cloudflare 로그인/사람 확인을 마친 뒤 여기서 Enter를 누르십시오. ')
        print('새 승인 탭을 엽니다. 2분 안에 본인 계정의 Wrangler 권한을 검토하고 허용하십시오.', flush=True)
        command = [str(WRANGLER), 'login', '--browser=false', '--use-keyring', '--scopes', *SCOPES]
        with subprocess.Popen(command, cwd=ROOT/'backend', stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True) as proc:
            opened = False
            for line in proc.stdout:
                match = re.search(r'https://dash\.cloudflare\.com/[^\s]+', line)
                if match and not opened:
                    url = match.group().rstrip('\"\'.,)')
                    try:
                        open_approval(url)
                    except Exception:
                        proc.terminate()
                        raise RuntimeError('승인 탭을 열지 못했습니다. 로그인 상태를 확인한 뒤 이 창에서 다시 시도하십시오.')
                    opened = True
            proc.wait()
        user = identity()
        if user is None:
            print('아직 승인이 완료되지 않았습니다. 키나 비밀번호를 출력하지 않고 다시 시도합니다.')
    accounts = user.get('accounts', [])
    if not accounts:
        raise RuntimeError('Cloudflare 계정이 없습니다. 대시보드에서 무료 Workers 계정을 준비하십시오.')
    if len(accounts) == 1:
        selected = accounts[0]
    else:
        for index, account in enumerate(accounts, 1):
            print(f"  {index}. {account.get('name', 'Cloudflare account')}")
        choice = int(input('사용할 계정 번호: '))
        if not 1 <= choice <= len(accounts):
            raise RuntimeError('계정 번호를 확인하십시오.')
        selected = accounts[choice-1]
    os.environ['CLOUDFLARE_ACCOUNT_ID'] = selected['id']


def worker_url(records):
    # Only accept this Worker's actual deploy output, never a user-supplied URL.
    for record in reversed(records):
        if record.get('type') != 'deploy' or record.get('worker_name') != 'hannun-meal-api':
            continue
        for target in record.get('targets', []):
            if isinstance(target, str):
                match = re.fullmatch(r'(?:https://)?(hannun-meal-api\.[a-z0-9-]+\.workers\.dev)/?', target)
                if match:
                    return 'https://' + match[1]
    raise RuntimeError('배포 결과에서 Worker의 공개 HTTPS 주소를 확인하지 못했습니다.')


def deploy_worker():
    print('\n② Worker를 배포합니다. 공식 CLI에 추가 계정/하위 도메인 질문이 나오면 확인하십시오.')
    print('유료 요금제 전환은 필요하지 않습니다. 요금제 결제 화면은 진행하지 마십시오.', flush=True)
    with tempfile.TemporaryDirectory(prefix='hannun-deploy-') as folder:
        output = Path(folder)/'deploy.jsonl'
        env = dict(os.environ, WRANGLER_OUTPUT_FILE_PATH=str(output), WRANGLER_SEND_METRICS='false')
        # Inherit the real terminal so the official CLI can handle its own prompts.
        result = subprocess.run([str(WRANGLER), 'deploy'], cwd=ROOT/'backend', env=env)
        if result.returncode or not output.exists():
            raise RuntimeError('Worker 배포가 완료되지 않았습니다. 계정 권한/무료 Workers 설정을 확인하십시오.')
        return worker_url([json.loads(line) for line in output.read_text().splitlines() if line.strip()])


def secrets():
    print('\n③ NEIS의 OPEN API 활용신청 탭에서 로그인하고 인증키 한 개를 발급/복사하십시오.')
    print('키는 아래 숨김 입력에 한 번만 붙여 넣습니다. 채팅/소스/공개 파일에 넣지 않습니다.', flush=True)
    while True:
        progress('awaiting-neis-key')
        key = getpass.getpass('NEIS 인증키 (입력 내용 숨김): ').strip()
        if not key:
            print('빈 값은 등록하지 않습니다.')
            continue
        while True:
            progress('checking-neis-key')
            try:
                check_key(key)
            except KeyCheckFailure as error:
                progress('neis-check-failed', error.code)
                print(str(error), flush=True)
                if error.replace_key:
                    break
                input('키를 다시 붙여 넣지 않고 재확인하려면 Enter, 중단하려면 Ctrl+C를 누르십시오. ')
                continue
            progress('saving-secrets')
            configure(REPO, key)
            del key
            print('동일한 키를 Actions와 Worker Secrets에 등록했습니다.')
            return


def directory():
    path = 'repos/'+REPO+'/actions/workflows/school-directory.yml'
    before = {run['id'] for run in api(path+'/runs?event=workflow_dispatch&per_page=10')['workflow_runs']}
    result = quiet(['gh', 'variable', 'set', 'DIRECTORY_READY', '--repo', REPO, '--body', 'true'])
    if result.returncode:
        raise RuntimeError('학교 목록 준비 상태를 저장하지 못했습니다.')
    api(path+'/dispatches', 'POST', {'ref':'main'})
    run_id = None
    for _ in range(30):
        runs = api(path+'/runs?event=workflow_dispatch&per_page=10')['workflow_runs']
        new = [run for run in runs if run['id'] not in before]
        if new:
            run_id = new[0]['id']
            break
        time.sleep(2)
    if run_id is None:
        raise RuntimeError('Actions 실행 접수 확인이 지연되었습니다. 저장소 Actions 탭에서 확인하십시오.')
    print('전국 학교 목록 생성·검증·Pages 배포를 기다립니다. 최대 15분입니다.', flush=True)
    result = quiet(['gh', 'run', 'watch', str(run_id), '--repo', REPO, '--exit-status', '--interval', '5'], timeout=900)
    if result.returncode:
        raise RuntimeError('학교 목록 Actions가 성공하지 않았습니다. 이전 배포는 유지됩니다.')
    for attempt in range(12):
        try:
            index = download(PAGES+'schools-index.json')
            digest = index['sha256']
            if not re.fullmatch(r'[a-f0-9]{64}', digest) or index['file'] != f'schools.{digest}.json':
                raise ValueError('index')
            data = download_bytes(PAGES+index['file'])
            if hashlib.sha256(data).hexdigest() != digest:
                raise ValueError('hash')
            schools = json.loads(data)['schools']
            validate(schools)
            if len(schools) != index['count']:
                raise ValueError('count')
            return index, schools
        except Exception:
            if attempt == 11:
                raise RuntimeError('Pages 실자료의 건수·해시 검증이 완료되지 않았습니다.') from None
            time.sleep(5)


def verify_meals(base, schools):
    school = next((s for s in schools if s[0:2] == ['B10','7010057']), schools[0])
    month = datetime.now(ZoneInfo('Asia/Seoul')).strftime('%Y%m')
    url = f'{base}/v1/meals/{school[0]}/{school[1]}/{month}'
    samples = []
    for _ in range(2):
        start = time.monotonic()
        from urllib.request import Request, build_opener
        from sync_school_directory import NoRedirect
        with build_opener(NoRedirect).open(Request(url,headers={'User-Agent':PUBLIC_USER_AGENT}), timeout=30) as response:
            body = response.read(2*1024*1024+1)
            meal = json.loads(body)
            if (meal.get('officeCode'), meal.get('schoolCode'), meal.get('yearMonth')) != (school[0],school[1],month) or not isinstance(meal.get('meals'),list):
                raise RuntimeError('월별 급식 응답 검증 실패입니다.')
            samples.append({'cache':response.headers.get('X-Meal-Cache'), 'milliseconds':round((time.monotonic()-start)*1000), 'mealRecords':len(meal['meals'])})
    return {'school':school[:3], 'yearMonth':month, 'requests':samples}


def save_endpoints(base):
    target = ROOT/'backend.properties'
    text = f'# Verified public endpoints only. No credentials.\nMEAL_API_BASE_URL={base}\nSCHOOL_DIRECTORY_BASE_URL={PAGES}\n'
    if target.exists() and target.read_text() != text:
        backup = OUT/('backend.properties.before-'+str(time.time_ns()))
        backup.write_bytes(target.read_bytes())
    target.write_text(text)


def main():
    if not sys.stdin.isatty():
        raise RuntimeError('키를 로그에 남기지 않도록 실제 터미널에서 실행하십시오.')
    OUT.mkdir(parents=True, exist_ok=True)
    print('한눈급식 실서비스 연결\nCloudflare 로그인 → 승인 → NEIS 키 숨김 입력 후 자동 배포/검증합니다.')
    progress('authorizing-cloudflare')
    authorize()
    progress('deploying-worker')
    base = deploy_worker()
    (OUT/'deployed-worker.json').write_text(json.dumps({'worker':base})+'\n')
    secrets()
    progress('publishing-directory')
    index, schools = directory()
    progress('verifying-meals')
    check = verify_meals(base, schools)
    save_endpoints(base)
    report = {'verifiedAt':datetime.now(ZoneInfo('Asia/Seoul')).isoformat(), 'worker':base, 'pages':PAGES, 'schoolCount':index['count'], 'meals':check, 'apkRebuilt':False}
    (OUT/'verified-services.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    progress('complete')
    print(f'\n연결 검증 완료: 학교 {index["count"]:,}개\nWorker: {base}\nPages: {PAGES}')
    print('공개 주소를 backend.properties에 저장했습니다. 기존 APK에는 앱 설정으로 주소를 넣거나 이 설정으로 다시 빌드해야 합니다.')
    print('서명키·APK·릴리스는 변경하지 않았습니다. 검증 결과: artifacts/data-setup-20261028/verified-services.json')


if __name__ == '__main__':
    while True:
        try:
            main()
            input('\n완료되었습니다. 창을 닫으려면 Enter를 누르십시오. ')
            break
        except (KeyboardInterrupt, EOFError):
            print('\n중단했습니다. 기존 서명키와 앱 데이터는 유지됩니다.')
            break
        except Exception as error:
            # Upstream exceptions can contain query credentials: never print them.
            print('\n'+(str(error) if type(error) is RuntimeError else '설정이 완료되지 않았습니다. 계정/네트워크 상태를 확인하십시오.'))
            try:
                input('이 창에서 다시 시도하려면 Enter, 중단하려면 Ctrl+C를 누르십시오. ')
            except (KeyboardInterrupt, EOFError):
                break
