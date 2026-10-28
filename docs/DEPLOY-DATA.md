# 1.2.0 데이터 서비스 배포

이 저장소의 기존 Android 앱을 위한 배포입니다. Pages는 학교 JSON 배포에 사용하며 별도 웹 앱/PWA가 아닙니다. 실제 배포 완료 기록은 테스트 문서와 구분합니다. API 키를 채팅·소스·공개 변수에 넣지 마십시오.

공식 앱은 공개 저장소 `Mangom72/hannun-meal`에 배포합니다. 학교 목록은 기존 공개 `Mangom72/hannun-meal-schools` Pages를 유지하며 앱 저장소의 `SCHOOL_DIRECTORY_EXTERNAL=true`가 중복 배포를 막습니다. 일반 사용자의 앱에는 공식 공개 주소가 기본 제공됩니다. 아래는 운영자·자체 배포용 절차입니다.

## 사전 조건

- 본인 소유 GitHub 저장소와 Actions/Pages 권한, Cloudflare Workers 계정, 발급된 NEIS 키 **한 개**.
- Pages source를 **GitHub Actions**로 설정. 현재 저장소에 기존 Pages workflow는 없었으므로 `school-directory.yml` 하나만 배포합니다. 다른 프런트엔드 배포를 이후 합칠 때는 그 산출물에 학교 JSON을 추가하고 배포 job은 하나만 유지하십시오.
- 저장소 공개 여부와 관계없이 서명 개인키·암호는 로컬 `.signing/`과 Actions Secrets에만 둡니다. 공개 학교 저장소에는 APK나 키를 넣지 않습니다. `scripts/prepare_school_repository.py`는 학교 관련 파일만 별도 저장소에 올립니다.
- `npm ci --prefix backend --ignore-scripts`, Node 22+ (CI는 24), JDK 17, 기존 Android SDK 36/Build Tools 35.0.0.

## 배포 순서

1. Cloudflare 공식 Wrangler로 본인 계정을 인증합니다.

   ```sh
   cd backend
   npx wrangler login
   npx wrangler whoami
   npm test
   npm run check
   ```

2. `backend/wrangler.jsonc`의 이름·rate-limit namespace ID(계정 내 미사용 ID)·TTL·허용 origin을 검토합니다. 브라우저가 없다면 `ALLOWED_ORIGINS="[]"` 상태로 Android만 사용할 수 있습니다. 브라우저를 허용할 때는 `https://OWNER.github.io`처럼 **경로 없는 origin**을 정확히 넣습니다. 공개 토큰을 APK에 내장하지 않습니다.

3. Worker를 배포합니다. Secret 미등록 상태에서는 upstream miss 요청이 CONFIGURATION 오류로 종료되며 임의 샘플 급식을 반환하지 않습니다.

   ```sh
   npm run deploy
   cd ..
   ```

4. GitHub CLI도 본인 계정·대상 저장소로 인증한 뒤, 같은 NEIS 키를 두 곳에 설정합니다. 이 명령은 실제 Secret 쓰기를 수행합니다.

   ```sh
   python3 scripts/configure_data_secrets.py --repo OWNER/REPO
   ```

   숨김 입력으로 한 번 받은 값을 `NEIS_API_KEY`라는 GitHub Actions Secret과 Worker Secret에 각각 보냅니다. CLI 출력은 캡처하며 값은 기록하지 않습니다. Worker 쪽만 실패하면 Actions는 이미 갱신되었을 수 있습니다. 같은 키로 다시 실행하십시오. 서명키를 만들거나 교체하는 명령이 아닙니다.

5. `Nationwide school directory and Pages` workflow를 수동 실행합니다. 17개 교육청/학교급/건수/중복/이전 대비 감소 검증 후에만 Pages를 배포합니다. 이후 한국 시간 월요일 03:17에 갱신합니다. 실패한 run이 기존 성공 사이트를 덮어쓰지 않는지 Actions에서 확인하십시오.

6. 본인의 실제 endpoint를 확인합니다. 학교 목록의 `schools-index.json`과 해시 JSON이 HTTP 200인지, 그 SHA-256와 건수가 맞는지 확인하십시오. Worker에서 실제 학교/조회 월의 응답과 `X-Meal-Cache`를 두 번 확인합니다. 키는 요청에 넣지 않습니다.

   ```sh
   curl -i https://WORKER.SUBDOMAIN.workers.dev/v1/meals/B10/7010057/202610
   ```

   예제 월은 고정 예시이며 실제 검증 시 현재 한국 월을 사용하십시오. 캐시 hit는 추가 NEIS 호출이 없고, 잘못된 학교·월·origin은 실패해야 합니다. 이미 warm이면 첫 요청도 HIT일 수 있습니다. Cloudflare의 cache/집계 제한은 location/isolate별입니다.

7. Android 앱에 **공개 주소 두 개만** 설정합니다. 방법은 다음 둘 중 하나입니다.

   - 일반 사용자는 공식 주소에 자동 연결되므로 앱에서 주소를 입력하지 않습니다.
   - `backend.properties.example`을 Git 제외 `backend.properties`로 복사하거나 빌드 환경 변수 `MEAL_API_BASE_URL`/`SCHOOL_DIRECTORY_BASE_URL`에 HTTPS 주소 지정. GitHub 릴리스 빌드는 같은 이름의 Actions **Variables**를 사용합니다. 여기에 NEIS 키를 넣지 않습니다.

8. 기존 정식 키로 빌드하고 설치 전 패키지·버전·SDK·해시·기존 서명을 검증합니다.

   ```sh
   ./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
   python3 scripts/create_update_manifest.py
   ```

   기존 앱 삭제나 캐시 초기화가 필요하지 않습니다. Room v1과 학교/위젯 설정을 유지합니다. 정식 키는 기존 `.signing/` 파일을 계속 사용합니다. 기존 GitHub APK Release 절차는 [RELEASING.md](RELEASING.md)와 같습니다.

## 실패 시

- 목록 생성 실패: 배포하지 않고 이전 Pages 결과 유지. 키를 로그로 확인하지 말고 Secret 설정·NEIS 상태·페이지 건수 검증을 확인합니다.
- 목록 다운로드 실패: 앱의 마지막 검증 파일 유지. 첫 다운로드를 한 번도 성공하지 않았다면 로컬 검색 자료가 없다는 안내가 나옵니다.
- NEIS 인증 실패/쿼터: Worker가 오류를 반환하고 성공 빈 월로 캐시하지 않습니다. 앱은 이전 Room 자료와 실패 안내를 유지합니다.
- Worker의 일시적 실패: 설정된 stale 한계 내에서만 서버 캐시를 반환하며 원래 성공 시각을 유지합니다. 기기의 별도 오프라인 캐시는 계속 볼 수 있습니다.
- 새로운 backend URL: 월 메타데이터는 source별로 구분해 이전 서버의 freshness를 새 서버의 성공으로 오인하지 않습니다.

## 공식 참고

- [Cloudflare Cache API: SWR/SIE 자동 미지원](https://developers.cloudflare.com/workers/runtime-apis/cache/)
- [Cache API의 데이터 센터 범위](https://developers.cloudflare.com/workers/reference/how-the-cache-works/)
- [Rate Limiting 바인딩과 분산 한계](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/)
- [Workers Request의 manual 리다이렉트 처리](https://developers.cloudflare.com/workers/runtime-apis/request/)
- [GitHub Pages custom workflow와 private 저장소 플랜](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)
- [NEIS 급식 데이터 설명](https://open.neis.go.kr/portal/data/service/selectServicePage.do?infId=OPEN17320190722180924242823&infSeq=1)
