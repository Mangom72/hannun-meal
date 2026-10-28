# 한눈급식 아키텍처 — 1.2.0

## 데이터 경로

```text
NEIS schoolInfo → 주간 Actions → Pages 정적 전국 목록 → 기기 파일·메모리 검색
NEIS mealServiceDietInfo ← Worker 월 API + edge cache ← Android 월 저장소
                                                          ↓
                                     Room meals / coverage / monthly_cache
                                                          ↓
DataStore → Schedule → MealSelector(Clock) → Compose UI / RemoteViews
```

실제 이전 설계·응답 계약·TTL·보안 한계는 [DATA-MIGRATION.md](DATA-MIGRATION.md), 배포는 [DEPLOY-DATA.md](DEPLOY-DATA.md)를 참조합니다. 기존 앱은 Kotlin/Compose Android이며 별도 웹 프런트엔드/PWA는 없습니다.

## Android 데이터와 수명

`core.AppGraph`는 Application 수명의 DI 컨테이너입니다. 공개 HTTP 전송, 정적 학교 목록, 월 API, Room, DataStore, 시계를 한 번 생성합니다. 기존 SupervisorJob과 lifecycle-aware ViewModel/Flow를 유지합니다. 네트워크는 저장소/ViewModel/WorkManager에서만 실행합니다. 렌더러는 캐시를 읽고 필요한 작업을 예약하며 렌더링 자체에서 네트워크를 호출하지 않습니다.

`MealApiClient`는 월별 응답을 검증하고 최근 32개 월을 메모리에 보관합니다. `MealRepository`는 전체 달을 단위로 동기화하고 중복·겹친 요청을 합치며 Room 트랜잭션으로 저장합니다. 월 키에는 학교·월·데이터 서버 source가 포함됩니다. 서버의 fetchedAt/freshUntil을 그대로 쓰므로 stale 성공을 새 성공으로 위장하지 않습니다. 만료된 서버 캐시를 받으면 기존 WorkManager의 제한된 재시도로 후속 갱신을 확인합니다.

`meals` 기본 키는 `(schoolId,date,type)`이고 `schoolId=교육청코드:학교코드`입니다. raw, dishes, 열량·영양·원산지·성공 시각을 유지합니다. `coverage`는 날짜별 성공/시도/오류, 새 `monthly_cache`는 전체 달의 freshness를 기록합니다. Room v1→v2는 새 테이블만 추가합니다. 실패 시 메뉴나 과거 성공을 삭제하지 않습니다. 초기 화면은 Room Flow를 즉시 관찰하고 백그라운드 갱신 중 목록을 비우지 않습니다.

학교 목록은 해시를 검증한 JSON 파일을 원자적으로 저장하고 검색 문자열을 메모리에 미리 정규화합니다. 타이핑은 기기 검색만 실행하며, 학교 화면 진입 시 필요할 때 별도 목록 갱신을 수행합니다. 원격 갱신에 실패해도 마지막 검증 파일을 유지합니다.

## 순수 도메인과 화면

`domain/selection`과 `domain/scheduling`은 Android/네트워크에 의존하지 않습니다. 주입한 Clock과 Asia/Seoul을 사용합니다. 구간 시작 포함/끝 제외, 24:00, 자정 교차, 제외 요일·식사, 고정/자동/다음 식사/수동 모드, 수동 복귀와 최대 21일 탐색을 유지합니다.

상태는 Present / Absent / Unknown / Failed를 구분합니다. Unknown은 동기화 필요로 중단하고 실패를 확정 결식으로 취급하지 않습니다. 저장된 메뉴와 오류/오래됨은 별도로 표시합니다. 실제 식사 시각과 메뉴 전환 시각도 분리합니다.

UI는 기존 Compose Material 3의 오늘·주간·학교·설정·위젯·업데이트 화면입니다. 시간 규칙·테마·색상·폰트·날짜 이동과 메뉴 상세 레이아웃을 유지합니다. 개인 API 키 입력 부분만 공개 Worker/Pages 주소 설정으로 바뀌며 학교 결과에 지역/학교급과 로컬 자동완성을 연결합니다. 학교와 위젯의 ID는 바뀌지 않습니다.

## 위젯과 백그라운드

DataStore의 `app`과 `widget_<ID>`를 분리합니다. 기존 `apiKey` 필드만 제거하며 학교·일정·모양·수동 위치·기존 위젯·GitHub 업데이트 인증을 유지합니다. 새 위젯만 기본 설정을 복사하고 삭제 시 해당 ID만 정리합니다.

4종 AppWidgetProvider가 기존 공통 RemoteViews 렌더러를 공유합니다. 크기별 요약·Chronometer 옵션·설정 버튼·immutable PendingIntent·위젯별 탐색 identity를 유지합니다. WidgetConfigActivity의 ID/provider 검증, 저장 후 RESULT_OK와 초기 렌더도 유지합니다.

- MealSyncWorker: 필요한 전체 월 조회, Room 저장, 렌더; 일시적 오류/서버 stale 상태만 최대 3회 재시도
- LocalRefreshWorker: 15분 최소 주기의 로컬 재선택
- WidgetEventReceiver: 부팅/시각/날짜/시간대/패키지 교체, 부정확 전환 알람
- WorkScheduler: 기존 자동 동기화 주기와 unique work 유지

정확 알람 권한·지속 foreground service·초당 폴링을 추가하지 않습니다. 절전·강제 종료·런처 정책에 따른 지연과 Chronometer 0초 정지 한계도 같습니다.

## 서버·배포·보안

Worker는 특정 NEIS endpoint만 조회하고 입력·페이지·본문 크기·시간 제한·CORS·요청 예산을 검사합니다. Cache API의 data-center-local 성격과 isolate-local 요청 합치기를 전역 보장으로 표현하지 않습니다. SWR/SIE는 직접 구현하며 실패를 정상 빈 달로 저장하지 않습니다. D1/KV/R2는 사용하지 않습니다.

전국 목록 Actions는 최소 건수·17개 교육청·3개 학교급·중복·건수 감소·페이지 완결성을 검사합니다. 결과가 유효할 때만 단일 Pages deploy job을 실행합니다. 실패한 갱신은 이전 사이트를 보존합니다.

NEIS 발급 키 한 개는 Workers/Actions Secret에만 저장하며 클라이언트 빌드·Pages·로그에 넣지 않습니다. 자동 요청 URL 추적을 제한하고 query redaction을 켭니다. 앱의 public endpoint는 credential/query/fragment를 거부합니다. 디버그 통합 검사만 loopback HTTP를 허용하며 정식 manifest는 cleartext를 허용하지 않습니다.

## 공개 APK 업데이트

`update/`는 공식 GitHub 공개 릴리스를 인증 없이 확인합니다. 이전 토큰 ciphertext와 기기 내 암호화 alias를 정리하고 자동 확인·Wi-Fi 선택은 유지합니다. API와 CDN에 인증 헤더를 보내지 않습니다. 최신 정식 릴리스의 자산 ID, 크기·SHA-256·패키지·버전·SDK·기존 인증서를 검사하고 FileProvider를 통해 시스템 설치 화면을 엽니다. 설치 직전에 다시 검사합니다.

WorkManager의 Application `Configuration.Provider` 지연 초기화와 12시간 업데이트 정책을 유지합니다. 설치는 사용자 확인이 필요합니다. 정식 키는 기존 `.signing/`와 Actions 서명 Secrets를 그대로 사용하며 자동 재생성하지 않습니다. 기존 CI·정식 release workflow에 데이터 검사와 공개 URL Variables를 더했습니다.
