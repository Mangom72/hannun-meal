# 개발 안내

[← 한눈급식 소개](../README.md)

앱 빌드, 데이터 서비스 운영, 자동 선택과 캐시 동작을 위한 참고 문서입니다.

## 기술 구성

| 영역 | 기술과 역할 |
| --- | --- |
| Android 화면 | Kotlin 2.2.21, Jetpack Compose BOM 2025.10.01, Material 3, ViewModel |
| 데이터·통신 | Coroutines·Flow, Kotlinx Serialization, Retrofit 2.11.0, OkHttp 4.12.0, jsoup |
| 로컬 저장 | Room 2.8.3: 급식·조회 상태·월 캐시 / DataStore 1.1.7: 앱·위젯 설정 |
| 위젯·백그라운드 | AppWidgetProvider·RemoteViews, WorkManager 2.10.5, 부정확 알람 |
| 급식 API | JavaScript ES modules, Cloudflare Workers Cache API, Wrangler 4.153.0 |
| 학교 목록 | Python 생성기, GitHub Actions, GitHub Pages 정적 JSON |
| 빌드·배포 | AGP 8.13.2, Gradle Wrapper 8.13, GitHub Actions 서명 APK 릴리스 |
| 테스트 | JUnit 4, Robolectric, MockWebServer, Room testing, Node.js test runner·Miniflare, Python unittest |

```text
NEIS schoolInfo → 학교 목록 Actions → GitHub Pages → 기기 내 학교 검색
NEIS mealServiceDietInfo → Cloudflare Worker·edge 캐시 → Android 월별 API 클라이언트
                                                        ↓
                                                   메모리·Room
                                                        ↓
DataStore 설정 → Clock 기반 선택 엔진 → Compose 화면 / RemoteViews 위젯
```

선택 엔진은 Android·네트워크에 의존하지 않으며 주입된 `Clock`으로 테스트합니다. Worker는 D1·KV·R2를 사용하지 않습니다. 자세한 설명은 [아키텍처](ARCHITECTURE.md)와 [데이터 계층](DATA-MIGRATION.md)을 참고하세요. 버전별 이전 기록에는 현재와 다른 과거 설정이 포함될 수 있습니다.

## 개발 시작하기

### 1. 준비 사항

- Git, **JDK 17**(컴파일러 포함)
- Android SDK Platform **36**, Build Tools **35.0.0** — Android Studio의 SDK Manager로 설치 가능
- 백엔드 개발·검사 시 **Node.js 24**와 npm(CI 기준)
- 학교 목록·배포 도구 검사 시 Python 3
- 실행·설치 시 Android 8.0 이상 기기 또는 에뮬레이터. `adb`는 Android SDK Platform-Tools에 포함됩니다.

아래 명령은 저장소 루트에서 실행합니다. Windows에서는 `./gradlew` 대신 `gradlew.bat`를 사용하세요.

### 2. 소스 받기·SDK 설정

```sh
git clone https://github.com/Mangom72/hannun-meal.git
cd hannun-meal
```

Android Studio에서 루트를 열고 SDK 경로를 설정하거나, Git 제외 파일 `local.properties`에 자신의 경로를 지정합니다.

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Gradle Wrapper가 필요한 Gradle과 의존성을 받으므로 첫 빌드에는 인터넷 연결이 필요합니다. `JAVA_HOME`은 JDK 17을 가리키도록 설정합니다.

### 3. 앱 빌드·실행

```sh
./gradlew assembleDebug
# 기기 또는 에뮬레이터를 연결한 경우
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android Studio에서 앱을 실행해도 됩니다. 디버그 빌드에는 **NEIS 키나 정식 서명키가 필요하지 않습니다.** 기본 공개 데이터 서비스 주소가 제공됩니다.

> 디버그 APK와 공개 정식 APK는 서명이 달라 서로 덮어쓸 수 없습니다. 기존 정식 앱의 데이터를 지우지 말고 별도 개발 기기·에뮬레이터를 사용하세요. 디버그 앱에서 정식 업데이트의 서명 불일치가 차단되는 것은 정상입니다.

### 4. 검사

```sh
# Android 단위 테스트·lint·디버그 빌드
./gradlew testDebugUnitTest lintDebug assembleDebug

# Worker 의존성 설치·테스트·배포 사전 검사
npm ci --prefix backend --ignore-scripts
npm --prefix backend test
npm --prefix backend run check

# 학교 목록·배포 도구 테스트
python3 -m unittest discover -s scripts/tests -v
```

`backend`의 `check`는 JavaScript 구문 검사와 `wrangler deploy --dry-run`입니다. 실제 배포나 운영 NEIS 조회 성공을 검증하는 명령은 아닙니다. Android 테스트는 선택 경계값, 미조회·결식·실패, 파싱, 캐시 보존, 설정·위젯·업데이트 등을 다룹니다. [CI 정의](../.github/workflows/ci.yml)와 [버전별 검증 기록](TESTING.md)도 참고하세요. 과거 기록의 테스트 수나 미완료 항목을 현재 버전 결과로 해석하지 마세요.

### 5. 자체 데이터 서비스 개발·배포 — 선택 사항

일반 앱 개발에는 별도 서버 배포가 필요하지 않습니다. 자체 서비스를 사용할 때만 Git 제외 `backend.properties`에 아래 두 공개 HTTPS 주소를 지정합니다. 같은 이름의 환경 변수가 파일보다 우선하며, 파일을 생략하면 [기본값](../backend.properties.example)을 사용합니다.

| 설정 | 용도 |
| --- | --- |
| `MEAL_API_BASE_URL` | 월별 급식 Worker의 기본 URL |
| `SCHOOL_DIRECTORY_BASE_URL` | 학교 목록 JSON을 제공하는 Pages의 기본 URL |
| `NEIS_API_KEY` | 서버 운영용 비밀값. Worker Secret과 학교 목록 Actions Secret에만 동일하게 등록 |

앱 설정에는 서버 주소 입력란이 없으므로 주소 변경 후 다시 빌드해야 합니다. URL에 인증 정보·쿼리·fragment를 넣지 마세요. NEIS 키는 Android 빌드 환경이나 공개 변수에 넣지 않습니다.

Worker 로컬 서버는 다음 명령으로 시작합니다.

```sh
npm --prefix backend run dev
```

실제 NEIS 조회에는 Git 제외 파일 `backend/.dev.vars`에 `NEIS_API_KEY`를 로컬 secret으로 설정해야 합니다. 키 값을 명령 인자나 로그에 남기지 마세요. 키가 없을 때 샘플 급식을 반환하지 않습니다. 자체 배포에는 Cloudflare 인증·Worker 설정과 NEIS 키, GitHub Actions·Pages 권한이 필요합니다. 절차는 [데이터 서비스 배포](DEPLOY-DATA.md)를 따르세요. `npm --prefix backend run deploy`는 실제 배포 명령입니다.

정식 APK는 기존 키와 서명 설정이 있어야 `./gradlew assembleRelease`로 빌드할 수 있습니다. **기존 정식 서명키를 재생성·교체하지 마세요.** 서명 Secrets와 릴리스 절차는 [배포 안내](RELEASING.md)에 정리되어 있습니다. 키·토큰·로컬 설정을 커밋하거나 로그에 출력하지 마세요.

## 프로젝트 구조

```text
app/
├── src/main/java/kr/han/meal/
│   ├── core/            # 앱 의존성 구성
│   ├── data/            # 월별 API·학교 목록·Room·DataStore·저장소
│   ├── domain/          # 모델·자동 선택·시간 규칙
│   ├── ui/              # Compose 화면·ViewModel
│   ├── widget/          # RemoteViews 위젯·설정·갱신
│   └── update/          # GitHub 업데이트·APK 검증·설치 연결
├── src/test/            # Android 단위·회귀 테스트와 fixture
└── schemas/             # Room 스키마
backend/                 # Cloudflare Worker·설정·테스트
scripts/                 # 학교 목록 생성·보안 검사·서명·배포 도구
pages/                   # 학교 목록 Pages 배포용 정적 파일
.github/workflows/       # Android 검사·서명 릴리스·학교 목록 배포
docs/                    # 설계·운영·버전별 검증 기록
backend.properties.example # 공개 데이터 서비스 기본 주소
version.properties       # 앱 버전·업데이트 저장소
CHANGELOG.md             # 버전별 변경사항
```


## 자동 선택 기본값

날짜와 시간은 기기 시간대와 관계없이 **Asia/Seoul**을 따릅니다. 아래 시간 구간은 시작을 포함하고 끝은 포함하지 않습니다.

| 시간 구간 | 기본 선택 |
| --- | --- |
| 00:00–08:00 | 오늘 조식 |
| 08:00–13:00 | 오늘 중식 |
| 13:00–19:00 | 오늘 석식 |
| 19:00–24:00 | 내일 조식 |

토·일은 기본 제외입니다. 성공적으로 조회했으나 해당 식사가 없는 경우에만 다음 허용 급식을 찾으며, 등록 없음 상태를 유지하도록 바꿀 수도 있습니다. 미조회는 결식으로 간주하지 않습니다. 탐색 범위는 요청 시작일을 포함한 21일입니다.

실제 식사 시간 기본값은 조식 07:30 / 중식 12:00 / 석식 17:30입니다. 수동 탐색은 기본적으로 다음 규칙 경계·식사 시각·자정에 자동 일정으로 복귀하며, 설정에서 수동 선택을 유지할 수 있습니다.

## 위젯과 업데이트

위젯은 ID별로 설정을 저장합니다. 새 위젯은 앱 기본값을 복사하지만 이후 기본값 변경이 기존 위젯 설정을 덮어쓰지는 않습니다. 위젯에서 이전·다음 탐색, 자동 복귀, 동기화 요청, 개별 설정을 할 수 있으며 작은 크기에서는 일부 정보가 요약됩니다.

앱 업데이트는 **설정 → 앱 업데이트**에서 관리합니다. 자동 확인은 12시간 주기로 예약되며 자동 다운로드와 Wi-Fi 조건을 선택할 수 있습니다. 설치 전 APK 크기·SHA-256·패키지·버전·SDK·기존 앱 서명을 확인합니다. **최종 설치에는 사용자의 시스템 확인이 필요합니다.**


## 데이터 출처와 제한

- **출처:** [NEIS 교육정보 개방 포털](https://open.neis.go.kr/)의 `schoolInfo`와 `mealServiceDietInfo`입니다. 자료의 등록·수정 시점과 외부 서비스 상태에 따라 실제 학교 안내보다 늦거나 누락될 수 있습니다. 알레르기 등 중요한 사항은 학교 안내도 확인하세요.
- **학교 목록:** 전국 초·중·고 자료를 생성·검증해 Pages에 게시합니다. 목록 workflow는 한국 시간 월요일 03:17에 예약되어 있으며 실제 실행은 GitHub Actions 사정에 따라 지연될 수 있습니다. 생성·검증 실패 시 기존 성공 배포를 유지합니다. 앱도 마지막으로 검증한 목록을 보존하며 입력할 때마다 NEIS를 요청하지 않습니다. 최초 목록 다운로드 전에는 오프라인 검색을 할 수 없습니다.
- **급식 조회:** `GET /v1/meals/{office}/{school}/{YYYYMM}`로 한 달을 받습니다. 기기의 메모리·Room과 Worker edge 캐시를 재사용하므로 새로고침이 항상 NEIS 실시간 재조회를 의미하지는 않습니다. 서버가 제공한 원래 조회 시각과 유효기간을 유지합니다.
- **갱신 주기:** 앱 동기화는 기본 6시간이며 1/3/6/12/24시간으로 설정할 수 있습니다. 기본 조회 범위는 과거 7일–미래 21일이며 날짜 탐색 시 필요한 월을 추가 조회합니다.
- **결식과 실패:** 성공 조회 결과에 식사가 없는 상태, 아직 조회하지 않은 상태, 요청 실패를 구분합니다. 실패를 빈 급식으로 저장하거나 기존 메뉴를 지우지 않으며 임의 메뉴도 만들지 않습니다. 오프라인에서는 저장된 자료만 볼 수 있습니다.
- **백그라운드 제한:** 위젯 렌더링 자체는 네트워크를 호출하지 않습니다. 캐시를 다시 선택하며 부정확 알람과 15분 주기 로컬 WorkManager 작업으로 갱신을 보완합니다. Doze·제조사 절전·강제 종료·런처 정책에 따라 늦어질 수 있으며 정각 전환을 보장하지 않습니다. 정확 알람 권한·지속 포그라운드 서비스·초당 폴링을 사용하지 않습니다.
- **카운트다운:** 기본값은 갱신 시점 기준 정적 표시입니다. 선택적인 네이티브 `Chronometer`는 0초에 앱을 깨우거나 자동 정지하지 않으므로 갱신이 늦으면 음수가 경과 시간을 뜻할 수 있습니다. 카운트다운 종료와 메뉴 전환은 별개입니다.
- **데이터 삭제:** 설정 초기화는 기본 학교를 포함한 앱 기본값을 초기화하지만 기존 위젯 설정과 급식 캐시는 유지합니다. 캐시를 직접 삭제하면 다음 성공 조회 전까지 오프라인 급식을 볼 수 없으며, 앱 삭제 시 로컬 데이터가 삭제됩니다.

## 개발 상태

현재 소스 버전은 **1.3.0 / versionCode 5**이며 [공개 릴리스](https://github.com/Mangom72/hannun-meal/releases/tag/v1.3.0)가 있습니다. 오늘·주간 화면, 자동 선택, 위젯, 오프라인 캐시, 공개 업데이트는 구현되어 있습니다. 1.3.0의 화면·위젯 관리 변경은 [변경사항](../CHANGELOG.md)을 참고하세요.

제조사별 야간·재부팅 후 갱신, 런처별 배치와 TalkBack 등 실제 기기 환경의 검증은 별도로 필요합니다. 확정된 향후 기능·일정의 로드맵은 제공하지 않습니다.

## 기여

별도 `CONTRIBUTING.md`와 `CODE_OF_CONDUCT.md`는 없습니다. 코드 변경 전 [프로젝트 개발 규칙](../AGENTS.md)을 확인하고 관련 테스트를 실행하세요. 공개 동작을 바꾸는 경우 경계값 또는 회귀 테스트를 추가하는 것이 프로젝트 규칙입니다.
