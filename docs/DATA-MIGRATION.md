# 1.2.0 데이터 아키텍처 이전

## 조사 범위와 보존한 기능

소스·리소스·Room schema·테스트·배포 스크립트·workflow·인계 문서를 조사했습니다. 이 이전본은 Kotlin/Compose Android 앱이며 웹 프런트엔드, PWA, 즐겨찾기 기능, 기존 Pages workflow는 없습니다. 사용자는 Android 앱 유지와 Pages 학교 목록 호스팅을 확인했습니다. 새 웹 앱을 만들지 않았습니다.

오늘/주간/학교/설정/위젯/업데이트 탐색, 날짜 이동, 학교 ID(`교육청:학교코드`), 테마, 시간 규칙, 식사 종류, 제외 요일, 수동 복귀, 4종 RemoteViews, 위젯별 설정, GitHub 비공개 APK 업데이트를 보존합니다. 인증키 입력 부분만 공개 서버 주소 설정으로 바뀝니다. 학교 종류/지역 메타데이터와 검색 자동완성을 기존 학교 선택 화면에 연결했습니다.

## 데이터 경로

```text
NEIS schoolInfo → 주간 GitHub Actions → Pages 학교 인덱스 + 해시 이름 JSON
                                             ↓ (목록 갱신 시만)
                                       기기 파일 + 메모리 검색

Android 날짜/식사/위젯 → Room 월 캐시 + 메모리 → Worker /v1/meals/{office}/{school}/{YYYYMM}
                                                        ↓ Cache API
                                                        ↓ miss일 때만
                                                 NEIS mealServiceDietInfo
```

기존 `NeisApi` 직접 호출과 빌드의 `NEIS_API_KEY` 주입을 제거했습니다. `NeisParser`/`DishParser`는 과거 fixture·메뉴 파싱 호환성 검사를 위해 남았으며 런타임 NEIS HTTP 경로가 아닙니다. 기존 API 키 항목은 DataStore 원문에서 그 필드만 제거합니다. 학교·테마·일정·위젯·업데이트 인증은 지우지 않습니다.

### 전국 학교 목록

`scripts/sync_school_directory.py`는 schoolInfo를 1,000행씩 수집하며 전체 건수·페이지 진전·필수 필드·교육청·학교 코드를 검증합니다. 초등/중등/고등학교를 ID로 중복 제거하고 동명 학교는 별도로 유지합니다. 10,000개 이상, 17개 교육청과 3개 학교급을 요구하고 이전 대비 이전 건수의 90% 미만으로 줄어드는 결과를 거부합니다. 실제 전국 목록 생성은 발급된 키가 필요하며 키 없는 샘플을 운영 목록으로 배포하지 않습니다.

`schools-index.json`에는 버전·생성 시각·건수·SHA-256·파일 이름이 있습니다. 데이터는 UTF-8 compact JSON의 6열 배열(교육청, 코드, 이름, 학교급, 지역, 주소)입니다. 해시 파일 작성 후 인덱스를 원자적으로 바꾸며, 이전 원격 해시 파일도 다음 배포에 함께 넣어 진행 중인 클라이언트 다운로드를 보호합니다. 실패한 workflow는 deploy job을 실행하지 않으므로 마지막 성공 Pages 배포가 유지됩니다.

Android는 검증한 파일을 기기 저장소에 보존하고 메모리에 정규화 검색 문자열을 미리 만듭니다. 학교 화면 진입 시 기존 파일을 먼저 읽으며 24시간 이후에만 목록 갱신을 시도합니다. 인덱스 해시가 같으면 큰 학교 JSON은 다시 받지 않습니다. 타이핑은 120ms 지연 후 기기 검색만 실행하며 외부 호출을 하지 않습니다. 부분 이름·지역/주소 토큰·학교급 필터를 지원하며 최대 100개 후보를 보여 줍니다. 이름이 같아도 ID·주소·지역으로 구별합니다.

### 월별 응답 계약

`GET /v1/meals/B10/7010057/202610`

```json
{"schemaVersion":1,"officeCode":"B10","schoolCode":"7010057","yearMonth":"202610","fetchedAt":"2026-10-26T00:00:00.000Z","freshUntil":"2026-10-26T01:00:00.000Z","staleUntil":"2026-10-27T01:00:00.000Z","meals":[]}
```

식사 행에는 ISO 날짜, `BREAKFAST`/`LUNCH`/`DINNER`, 메뉴별 이름·알레르기 번호·원문, 전체 원문·열량·영양·원산지가 있습니다. 정상 무자료만 HTTP 200의 빈 `meals`입니다. 인증·쿼터·네트워크·잘린 페이지·잘못된 학교/날짜 응답은 오류 코드와 비성공 HTTP 상태로 구분합니다. NEIS 오류 본문·메시지·키를 클라이언트에 돌려주지 않습니다.

월 경계 및 현재/다음 월 판단은 Asia/Seoul입니다. 월 전체를 조회하고 필요할 때만 페이지를 이어 받습니다. 기본 범위는 2020년부터 현재 한국 월+2개월까지이며 설정할 수 있습니다. 입력은 17개 교육청 allowlist·ASCII 대문자/숫자 7자리 학교 코드(`C035903` 같은 실제 국립학교 코드 포함)·유효 YYYYMM만 허용하며 임의 URL/NEIS 쿼리를 전달하지 않습니다.

### 캐시 세 층

| 계층 | 구현 | 무효화/재사용 |
| --- | --- | --- |
| 기기 메모리 | `MealApiClient` 최근 32개 월, `AppGraph` Room 상태 Flow | 서버 `freshUntil` 기준; 같은 월 중복 요청 직렬화 후 재사용 |
| 기기 영구 저장 | 기존 meals/coverage + Room v2 monthly_cache | 월 전체 트랜잭션, 서버 시각 보존, 백그라운드 갱신, 오류 시 기존 행 보존 |
| Cloudflare edge | Cache API, `/__cache/v1/meals/교육청/학교/YYYYMM` | 명시적 메타데이터 freshness/SWR/stale-if-error |

기본 fresh TTL은 현재 월 1시간, 다음/허용 미래 월 6시간, 과거 월 7일, 정상 빈 결과 15분입니다. `SWR_SECONDS=300`, `STALE_IF_ERROR_SECONDS=86400`이며 Wrangler vars로 바꿀 수 있습니다. 각 TTL은 최대 30일, fresh와 stale 보관 기간의 합은 최대 31일로 제한합니다. Worker는 freshness보다 길게 원본 성공을 보관하고, SWR 구간에는 기존 응답을 먼저 보내 `ctx.waitUntil()`로 갱신합니다. 앱이 만료된 서버 성공을 받은 경우 Room을 유지하며 기존 WorkManager 재시도(30초부터, 최대 3회)로 후속 갱신을 확인합니다. 그 뒤에는 갱신을 기다리되 일시적 실패에만 원래 `staleUntil` 이내 응답을 사용합니다. stale 응답의 성공 시각을 현재 시각으로 다시 찍지 않습니다. 전용 헤더 `X-Meal-Cache`/`X-Meal-Stale`과 응답 시각으로 상태를 확인할 수 있습니다.

Cache API는 SWR/SIE를 자동 처리하지 않으며 데이터 센터 단위 캐시입니다. 메모리의 진행 중 요청 합치기도 isolate 단위 최선 노력이며 전역 단 한 번을 보장하지 않습니다. 공유하는 값은 일반 문자열/숫자이고 요청·응답 스트림을 다른 요청에 넘기지 않습니다. 이 경로를 실제 workerd에서도 검사합니다.

Room v1→v2 마이그레이션은 새 테이블만 추가합니다. 기존 날짜별 메뉴·확정 결식·실패 정보를 유지하고 첫 월 응답부터 월 메타데이터를 채웁니다. 실패로 기존 meals를 삭제하지 않습니다. 이전보다 오래된 서버 성공이 오면 더 최신 기기 월 캐시를 덮어쓰지 않습니다. 새 월 조회 뒤 같은 월 날짜 이동은 추가 Worker/NEIS 요청 없이 Room에서 선택합니다. 만료 시 UI를 비우지 않고 WorkManager로 갱신합니다. 기기 오프라인에서는 오래된 메뉴도 마지막 성공 시각과 함께 볼 수 있으며, 이는 Worker의 유한 stale-if-error와 별도의 오프라인 UX입니다.

### 보안·할당량

NEIS 발급 키 한 개를 Worker Secret과 Actions Secret에 동일하게 넣습니다. `configure_data_secrets.py`는 한 번 입력받은 같은 바이트를 두 CLI의 stdin으로 전달합니다. 키를 파일·명령 인자·출력에 쓰지 않으며 부분 실패를 알립니다. 서명키는 기존 `.signing/` 및 기존 Android 서명 Secrets와 별개이며 교체하지 않습니다.

CORS는 `ALLOWED_ORIGINS`의 HTTPS origin과 정확히 일치하는 경우만 허용합니다. 네이티브 Android처럼 Origin이 없는 요청은 허용합니다. CORS는 인증이 아니므로 공개 데이터 API 자체를 비밀로 취급하지 않습니다. 고정 NEIS endpoint만 호출하며 리다이렉트를 따라가지 않습니다. 응답 크기·페이지·기간·12초 upstream 전체 시간·isolate 동시 작업 수를 제한합니다.

내장 Rate Limiting 바인딩은 클라이언트 IP별 120회/분, 캐시 miss 갱신은 location별 20회/분 기본값입니다. NAT에서는 정상 사용자가 예산을 공유할 수 있고 Cloudflare의 분산 계수는 엄격한 전역 카운터가 아닙니다. 무료 요청 수와 NEIS 계정 일일 쿼터가 무제한이라고 가정하지 않습니다. 실제 사용량·계정 한도를 확인해 값을 조정해야 합니다. D1/KV/R2/Durable Objects는 추가하지 않았습니다.

로그는 구조화된 고정 오류 코드·학교/월·소요 시간만 남깁니다. URL query redaction을 켜고 자동 invocation URL 로그/트레이스는 껐습니다. NEIS query의 KEY가 자동 하위 요청 추적에 남지 않게 하기 위한 선택입니다. 원격 오류 메시지나 예외 문자열은 출력하지 않습니다.

## 관련 변경 파일

- `backend/`: 가벼운 ESM Worker, Wrangler 설정/잠금 파일, Node 및 workerd 테스트
- `scripts/sync_school_directory.py`, `configure_data_secrets.py`, `scripts/tests/`: 전국 목록/동일 키 배치/안전성 검사
- `.github/workflows/school-directory.yml`: 새 단일 Pages 배포 경로; 기존 CI/release workflow 유지·확장
- `data/remote/`: 공개 HTTPS 전송, 월 API, 검증한 정적 학교 목록과 로컬 검색
- `data/repository/`, `data/local/`, Room schema v2: 기존 캐시 보존 및 월 메타데이터
- `core/`, `worker/`, 기존 UI/위젯의 데이터 연결부: 네트워크 경로 교체; 시간 선택 엔진·시각 디자인 유지
- `backend.properties.example`: 비밀이 아닌 endpoint만 설정

배포 순서와 실제 검증 한계는 [DEPLOY-DATA.md](DEPLOY-DATA.md), [TESTING-1.2.0.md](TESTING-1.2.0.md)를 참조합니다.
