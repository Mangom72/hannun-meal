# 1.2.0 데이터 계층 이전 검증

검증일: 2026-10-27, Asia/Seoul. 기존 Kotlin/Compose Android 앱에서 데이터 계층을 교체했습니다. Pages는 학교 JSON 호스팅이며 새로운 웹 앱이 아닙니다. 원격 배포와 실제 NEIS 인증은 수행하지 않았습니다.

## 실행 결과

| 검사 | 실제 결과 |
| --- | --- |
| `testDebugUnitTest lintDebug assembleDebug assembleRelease` | **BUILD SUCCESSFUL in 3m 42s**, 113 tasks |
| Android JUnit/Robolectric | **92개**, 실패/오류/건너뜀 0 |
| Android lintDebug | 오류/치명적 오류 0, 경고 **59개** |
| `python3 -m unittest discover -s scripts/tests -v` | **20개 통과** |
| `npm test --prefix backend` | **14개 통과**, 실제 workerd 포함 |
| `npm run check --prefix backend` | JS 구문 검사·Wrangler 배포 dry-run 성공 |
| actionlint 1.7.12 | CI/release/학교 Pages workflow 3개, 종료 코드 0 (`-shellcheck=`) |
| APK manifest 생성·apksigner·zipalign 16KB | 성공, 패키지/버전/minSdk/단일 기존 인증서 확인 |
| `check_client_security.py --apk artifacts/release/hannun-meal.apk` | APK/Pages에 서버 키 필드 없음; 실제 제공된 NEIS 키는 없음 |
| 원래 `.signing/` 두 파일 | `TRANSFER_MANIFEST.json`의 원본 SHA-256와 동일 |
| 정식 APK network policy | `usesCleartextTraffic=false`, debug용 networkSecurityConfig 없음 |

최종 명령은 기존 로컬 JDK 17·SDK 36/Build Tools 35.0.0을 사용했습니다. 시스템 Java나 PATH를 변경하지 않았습니다. 메모리 압력을 줄이기 위해 최종 Gradle 실행에 `--no-daemon -Dorg.gradle.jvmargs='-Xmx1536m -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process`를 사용했습니다.

로그: [Gradle](verification/migration-1.2.0-gradle.txt), [Python](verification/migration-1.2.0-python.txt), [Worker](verification/migration-1.2.0-worker-tests.txt), [Wrangler dry-run](verification/migration-1.2.0-worker-check.txt), [actionlint](verification/migration-1.2.0-actionlint.txt), [기계 판독 요약](verification/migration-1.2.0-results.json). 이 이전본에는 `.git`이 없어 `codex review --uncommitted` 대신 수정 전 소스 백업과 파일 비교·직접 검토를 사용했습니다.

## 검증한 경계

- 월 경계·윤년·한국 시간의 UTC 경계, 아침/점심/저녁·알레르기·열량·영양·원산지 정규화.
- 정상 빈 결과와 인증/쿼터/네트워크/잘못된 응답 구분, 페이지 합계 변화·반복·잘림·중복 충돌 거부.
- 메모리·Room 월 캐시의 재사용/만료, 중복 요청 합치기, 실패 시 기존 메뉴 보존, 더 오래된 서버 응답의 덮어쓰기 방지.
- Worker TTL·명시적 SWR·시간 제한 stale-if-error·성공 시각 보존, 허용 origin/고정 endpoint/리다이렉트 차단/rate limit, 반사된 키의 반환·캐시 차단.
- 학교 목록의 페이지네이션·중복 제거·동명 학교 구분·17개 교육청/학교급 검증·불완전 목록 거부·실패 시 이전 파일 보존.
- 한국어 부분 이름·지역/학교급 검색, 다운로드 후 입력 시 네트워크 0건, 인덱스 SHA가 같으면 큰 JSON 재다운로드 생략.
- Room v1의 기존 메뉴/coverage가 v2에서 유지됨. 기존 설정 JSON의 개인 NEIS 키 필드만 제거하고 학교·테마·위젯·알 수 없는 필드를 보존함.

## 실제 Android 화면 검사

별도 headless API 36 에뮬레이터에서 기존 앱을 실행했습니다. 물리 기기나 사용자 데스크톱을 조작하지 않았습니다. 최종 화면 검사는 720×1280, density 320(360dp 폭)에서 수행했습니다. Compose 화면 구조·내비게이션·테마는 유지됩니다.

시험 서버는 127.0.0.1에만 바인딩하고 에뮬레이터의 adb reverse로 연결했습니다. **12,002개 가상 학교 및 명시적으로 ‘통합검증용’이라고 표시한 메뉴**를 사용했습니다. 이 파일들은 `artifacts/migration-verification/fixture-pages/`에만 있으며 운영 `pages/`에 넣지 않았습니다. 이는 실제 전국 NEIS 자료 검증을 대신하지 않습니다.

1. 기존 데이터 설정 화면에서 공개 서버/목록 URL 두 개를 저장했습니다. URL 키보드가 경로를 자동 대문자로 교정하는 문제를 발견해 URI 입력/자동 교정 해제로 수정했습니다.
2. 다운로드한 목록에서 부분 검색 후 `검증학교00001`을 기존 확인 창으로 선택했습니다. ID는 `C10:8000001`이며 주소·지역·학교급이 표시됩니다. 검색 타이핑은 목록 다운로드를 추가하지 않았습니다.
3. 10월과 11월을 각각 한 번 요청하여 Room에 월 메타데이터 2개와 식사 **183행**(61일×3종)이 저장됐습니다. 기존 홈·주간·메뉴 상세에서 합성 급식·알레르기·열량·영양·원산지를 확인했습니다.
4. 날짜 이동·주간 화면·최종 debug APK 교체 및 앱 재실행 후에도 요청 수는 `/202610` **1회**, `/202611` **1회**였습니다. 새 NEIS 호출을 실제로 측정한 것은 아니며 이 단계의 카운터는 Android→시험 서버 요청입니다.
5. 시험 서버를 종료하고 에뮬레이터 wifi/data를 끈 뒤 앱을 다시 열고 수동 새로고침했습니다. 저장된 메뉴와 ‘오프라인’, ‘네트워크 연결 실패’ 안내가 함께 표시됐습니다. 전후 메뉴 183행의 정렬된 SQL 출력 SHA-256가 모두 `2df79f5534f353bc018a82c575d90cc8d3ea88521762f12d98fe3cdf5504893d`로 동일했습니다.
6. 별도 기존 AVD에서 정식 1.1.0/code2→1.2.0/code3 패키지 교체가 성공했고 Room v2와 위젯 ID 2/3/4/5가 유지됐습니다. 이 AVD의 화면은 시스템 ANR에 가려져 있어 해당 단계에서 전체 화면 정상 동작까지 확인한 것으로 간주하지 않습니다.

증거는 `artifacts/migration-verification/`의 `monthly-meals.png`, `weekly-view.png`, `meal-detail.xml`, `offline-refresh.png/xml`, 요청 카운터 JSON, 메뉴 전후 해시 JSON, 위젯 before/after 자료에 있습니다. 최초 에뮬레이터 부팅 시 자원 압력으로 SystemUI/GMS ANR이 발생했습니다. 해당 이벤트는 기록에 보존했으며 대상 `kr.han.meal`의 crash/ANR 이벤트는 없었습니다. 시험 서버와 이번 에뮬레이터는 종료했습니다. PC는 종료하지 않았습니다.

## 요청 수와 지연 측정 범위

실제 로컬 workerd에서 HTTP 요청 10개(서로 다른 2개 학교-월, 한 묶음은 동시 요청)를 보내 **mock NEIS 호출 2회**를 확인했습니다. 이 실행의 cold 386.69ms/warm 17.76ms는 **30ms 지연을 주입한 시험 upstream과 로컬 런타임의 측정**입니다. 실제 NEIS/Cloudflare latency나 운영 성능 향상 수치로 일반화하지 않습니다. Node 단위 검사에서는 동일 월 동시 20개 요청이 mock upstream 1회로 합쳐졌습니다. 데이터 센터/isolate가 다르면 중복 upstream 요청이 생길 수 있습니다.

## 정식 산출물

- `artifacts/release/hannun-meal.apk` = `app/build/outputs/apk/release/app-release.apk`
- `kr.han.meal`, 버전 **1.2.0 / 3**, minSdk **26**
- 크기 **9,649,132 bytes**
- APK SHA-256: `e445720cf002b95a8ce3e352d4d858ea9b5cbe529bcadf1ee5c57a6fc6ad4ebf`
- 기존 인증서 SHA-256: `9851c533f0375ec5e3ef6cb588d1d2378b4a0adaa3f4292f1a372f9d594316b7`
- `artifacts/release/update.json`은 위 APK에서 재생성했습니다. debug APK는 정식 APK와 인증서가 다릅니다.

## 남은 배포·실서비스 확인

발급된 NEIS 키/Cloudflare 인증이 없으며 대상 GitHub 저장소도 현재 인증에서 조회되지 않습니다. Secret 등록, 실제 전국 JSON 생성, Pages/Worker 배포, Actions runner, 실제 NEIS 월 응답·운영 rate limit·원격 캐시 지연, 실기기 업데이트/제조사 백그라운드 정책은 미검증입니다. APK에는 배포 주소가 기본 지정되지 않아 기존 설정 화면에서 실제 공개 URL을 저장해야 합니다. 서버가 없는 상태를 샘플 메뉴로 대체하지 않습니다.

기존 비공개 소스를 공개하지 않습니다. GitHub Free의 비공개 저장소 Pages 제약과 수동 절차는 [DEPLOY-DATA.md](DEPLOY-DATA.md)를 참조합니다. 같은 발급 키를 두 Secrets에 넣는 도구를 준비했으며 실제 자격 증명 쓰기는 하지 않았습니다.

## 추가한 도구

- 공식 `cloudflare/skills`의 `workers-best-practices`를 검토 후 사용자 범위에 설치했습니다. 커밋 `86505e44f87d801aa1864a3c4109e9a8ae5c409f`, Markdown 4개, 실행 스크립트/설치 hook 없음. Worker의 I/O 공유·캐시·보안 검토에 사용했습니다.
- 프로젝트 개발 의존성 Wrangler **4.153.0**, Miniflare **5.20261023.0-beta**를 잠금 파일로 고정하고 `--ignore-scripts`로 설치했습니다. 공식 CLI dry-run과 실제 workerd 테스트에 사용했으며 서버 런타임 의존성은 없습니다. 인증/배포 명령은 실행하지 않았습니다.
- 공식 `rhysd/actionlint` **1.7.12** 바이너리를 사용자 캐시에만 설치했습니다. release SHA-256 `8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8` 확인 후 workflow 3개의 문법 검증을 통과했습니다. sudo/시스템 설정 변경은 없습니다.
