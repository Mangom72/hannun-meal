# 한눈급식 UI/UX 개편 — 2026-10-28

기존 Kotlin/Compose 앱을 직접 수정했습니다. 이름은 **한눈급식**, minSdk 26, 기존 Compose BOM/Material 3 **1.4.0**을 유지합니다. 새 프레임워크·의존성·설정 필드·서버 기능을 추가하지 않았습니다. 1.2.0 화면 9장을 기존 디자인 참고로 사용했으며, 그 학교·급식은 실제 NEIS 자료로 취급하지 않았습니다.

## 달라진 화면

- **홈:** 학교와 지역을 앱바에 모으고 날짜·식사 전환 뒤에 한 끼만 표시합니다. 기존 큰 요약 카드와 중복된 하루 메뉴 목록을 제거했습니다. 식사 시간·열량·상세 진입을 메뉴 머리말에 배치하고 자동 선택 복귀를 식사 탭 옆에 두었습니다. 날짜 선택기, 수동 날짜 이동, 자동 선택 엔진, 결식·미조회·실패의 구분은 유지합니다. 제외한 요일/식사도 수동 탐색에서는 확인할 수 있고 자동 선택 규칙을 바꾸지 않습니다.
- **상세:** 좁은 AlertDialog를 확장형 Material 하단 시트로 바꿨습니다. 학교·날짜·식사·닫기를 고정하고 메뉴와 알레르기 **번호+식품명**을 먼저 보여 줍니다. 열량, 영양, 원산지, 전체 알레르기 안내, NEIS 원문을 모두 유지하며 보조 항목은 펼쳐서 확인합니다.
- **주간:** 범위 선택을 한 줄 메뉴로 모으고 날짜 칩·식사 필터·선택 날짜 강조를 연결했습니다. 날짜 칩은 해당 일자로 이동합니다. 기존 한 주/등교일 5일 보기, 제외 요일, 간결한 메뉴 옵션을 유지합니다.
- **학교:** 입력 중 자동 검색, 학교급 필터, 지역·주소, 현재 학교 표시, 로딩·결과 없음·선택 확인을 정리했습니다. 학교급은 검색 건수 제한 전에 적용합니다. 검색 입력 시 안내 헤더를 접고 안정적인 목록 key로 키보드 전환 후 잘림을 줄였습니다. 기기 검색을 유지합니다.
- **설정/일정:** 관련 설정을 그룹으로 모으고 큰 카드 대신 간결한 행을 사용합니다. 일상적인 선택·요일·식사 시간과 펼칠 수 있는 세부 시간 규칙을 구분했습니다. 저장 버튼은 화면 아래에 고정합니다. 기존 규칙·분할·합치기·결식 정책·수동 복귀·식사 시간 및 저장 형식을 유지합니다. 스위치 행은 한 개의 접근성 동작으로 전환합니다. 시스템 뒤로가기는 하위 설정에서 설정 목록으로 돌아옵니다.
- **위젯:** 메뉴 공간을 크기와 글자 배율로 계산하고 설정 버튼 등 동작에 48dp를 확보합니다. 아주 작은 높이에서는 메타데이터를 접고 요약을 우선합니다. 주간 위젯의 남은 날짜는 전체 보기로 연결합니다. 배경·글자색에 야간 리소스를 적용했으며, 캐시된 RemoteViews가 테마 전환 뒤에도 이전 밝은 글자색을 강제로 사용하던 문제를 수정했습니다. 위젯은 **시스템** 야간 모드를 따르고 기존 개별 색상·불투명도 설정을 유지합니다.

## 디자인과 아이콘

초록/민트 기반의 명시적 색상 역할과 절제된 따뜻한 강조색을 사용합니다. 기존 라벤더·살구 프리셋과 시스템/밝은/어두운 테마, Material You를 유지합니다. 사용자 글자 크기를 본문뿐 아니라 라벨까지 적용하며 고정 높이 대신 유연한 행·스크롤 가능한 칩을 사용합니다. 네이티브 시트와 선택 상태의 기본 동작을 사용하고 Expressive 전용 alpha 라이브러리는 추가하지 않았습니다.

그릇과 두 개의 식재료 실루엣을 단순화한 벡터 아이콘을 적용했습니다. 배경을 adaptive background로 분리했고, foreground의 바깥은 투명합니다. 주요 도형은 108dp 뷰포트의 중앙 **66dp 안전 원** 안에 있습니다. API 26+ adaptive 설정과 API 33+ monochrome 레이어를 검증했습니다.

| 홈 | 주간 선택 날짜 | 아이콘 |
| --- | --- | --- |
| ![홈](../artifacts/ui-refresh-20261028/screens/home-light.png) | ![주간](../artifacts/ui-refresh-20261028/screens/weekly-selected-day.png) | ![아이콘](../artifacts/ui-refresh-20261028/icon-previews/circle-color.png) |

위 이미지는 로컬 산출물입니다. 전체 사진·XML은 `artifacts/ui-refresh-20261028/screens/`, 아이콘의 원형/둥근 사각형/사각형 및 단색 미리보기는 `icon-previews/`에 있습니다.

## 변경 파일

- `ui/MealApp.kt`: 홈·주간·앱바·날짜/탭 탐색·뒤로가기
- `ui/MealComponents.kt` **신규**: 상세 시트·메뉴/알레르기·날짜 선택기·공통 펼침 항목
- `ui/Theme.kt`: 색상 역할·서체 크기·도형·시스템 테마
- `ui/SettingsScreens.kt`, `WidgetScreens.kt`: 검색·설정 그룹·일정·위젯 설정
- `ui/MealViewModel.kt`: 로컬 검색의 학교급 전달과 선택 날짜의 재시도
- `widget/WidgetRenderer.kt`, `res/layout/widget_*.xml`, `values[-night]/widget_colors.xml`, `drawable-night/`: 크기·글자·터치 영역·야간 위젯
- `res/drawable/ic_meal.xml`, `ic_meal_mono.xml`, `values/strings.xml`의 배경색: adaptive/themed 아이콘. 앱 이름은 변경하지 않았습니다.
- `UiPresentationTest.kt`, `LauncherIconTest.kt` **신규**, `WidgetRenderTest.kt` 확장: 선택 날짜·알레르기·대비·글자·아이콘·위젯 경계 회귀 검사

데이터 계층, Room schema, 선택/시간 엔진, 학교 ID, 업데이트 구현, Android/Pages/Worker 배포 설정과 서명 파일은 수정하지 않았습니다. 전체 수정 목록과 수동 diff는 `artifacts/ui-refresh-20261028/changed-files.json`, `review.diff`입니다. `.git`이 없는 이전본이므로 `codex review --uncommitted` 대신 변경 전 소스 백업과 파일 비교·직접 검토를 사용했습니다.

## 실행한 검사

| 검사 | 결과 |
| --- | --- |
| `testDebugUnitTest lintDebug assembleDebug` | **BUILD SUCCESSFUL in 1m 52s** |
| Android JUnit/Robolectric | **100개 통과**, 실패/오류/건너뜀 0 |
| lintDebug | 오류 0, 경고 **59개** |
| Python 배포 도구 검사 | **20개 통과** |
| APK 클라이언트 키 검사 | 통과, NEIS 서버 키 필드 없음 |
| 기존 `.signing/` 파일·정식 APK | 변경 전 SHA-256와 동일 |
| 고정 브랜드 프리셋 대비 | 밝음/어두움 3개 팔레트의 주요 텍스트 역할 8쌍 모두 4.5:1 이상 |
| 아이콘 | API 33 adaptive/monochrome 로딩, foreground 안전 원, 6개 마스크/색상 렌더 통과 |
| 위젯 | 110dp 최소 크기부터, 사용자 글자 140%, 240/260dp 버튼 경계, 본문·하단 버튼 영역 검사 통과 |
| 캐시된 위젯 테마 변경 | 밝은 상태에서 만든 RemoteViews를 야간에 적용했을 때 야간 글자색·대비 검사 통과 |

명령은 기존 JDK 17과 SDK Platform 36/Build Tools 35.0.0에서 실행했습니다. 최종 Gradle에는 `--no-daemon -Dorg.gradle.jvmargs='-Xmx1024m -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process`를 사용했습니다. 서버·목록 생성 코드는 변경하지 않아 Worker 배포를 실행하지 않았습니다.

로그: `artifacts/ui-refresh-20261028/android-checks.txt`, `python-checks.txt`, `results.json`. 변경 전 소스·문서는 같은 폴더의 `before-ui-source.tar.gz`에 보존했습니다.

## 실제 렌더와 상호작용

별도 headless API 36 에뮬레이터에서 **720×1280 / 360dp 폭**으로 확인했습니다. 소스에 가상 메뉴를 넣지 않았습니다. 기존 로컬 시험 서버와 해당 에뮬레이터의 **가상 학교/급식**만 사용했으며 운영 Pages·Worker·APK assets에는 포함하지 않았습니다.

- 기본 홈에서 6개 메뉴가 **각 한 번씩**, 하단 내비게이션 위에 완전히 보이는 것을 native XML 좌표와 실제 PNG로 확인했습니다. 중복 하루 메뉴 카드는 없습니다.
- 식사 탭, 날짜 이전/다음, 날짜 선택기, 자동 선택 복귀를 실제 눌러 확인했습니다. 토요일은 제외 안내와 정상 결식 상태를 표시했습니다.
- 상세 시트를 스크롤하고 영양·원산지·NEIS 원문을 펼쳐 읽었으며 닫기는 계속 접근 가능했습니다.
- 동명 학교의 서로 다른 지역, 학교급 필터, 결과 없음, 실제 선택 확인을 확인했습니다.
- 주간 날짜 이동/강조, 중식 필터, 간결한 메뉴, 등교일 5일 범위와 월 경계(10/28–11/3)를 확인했습니다.
- 밝은/어두운 테마, **앱 글자 140% + 시스템 font_scale 1.3**, Material You를 실제 렌더했습니다. 큰 글자는 정상적으로 줄 바꿈·스크롤하며 메뉴 일부가 화면 아래에 있을 수 있습니다. 잘린 고정 컨트롤이나 겹침으로 판단하지 않도록 본문 스크롤 영역과 고정 내비게이션을 구분했습니다.
- 실제 런처의 2×2 위젯(이 런처의 본문 크기 약 124×138dp), 야간 테마 전환, 시스템 큰 글자, 위젯 설정 화면을 확인했습니다. 작은 위젯의 말줄임은 의도적인 요약이며 눌러 전체 메뉴를 볼 수 있습니다. 야간 메뉴 글자가 어둡게 남던 문제를 관찰·수정하고 최종 APK에서 다시 확인했습니다.
- 대상 앱의 crash/ANR 이벤트는 없었습니다. 초기 에뮬레이터 부팅의 SystemUI ANR은 별도 기록에 남겼습니다. 사용자 PC의 창·포커스는 이동하지 않았습니다.

## 산출물과 한계

미리보기: `artifacts/ui-refresh-20261028/hannun-meal-ui-preview-debug.apk`. 버전은 기존 **1.2.0 / code3** 그대로이고 **debug 서명**입니다. 정식 앱의 서명과 다르므로 정식 앱의 업데이트용으로 취급하지 마십시오. 기존 `artifacts/release/hannun-meal.apk`는 이전 정식 산출물 그대로 보존했습니다. 신규 정식 릴리스·버전 변경·게시·push·배포는 하지 않았습니다.

- 미리보기 크기: **14,391,213 bytes**
- SHA-256: `a9f5a85f9d95aaed29b4054fdb3b31e1552fe978fff993c1e2073e66992752d9`
- 설치 전 패키지·버전·minSdk·해시·에뮬레이터 기존 앱의 debug 인증서를 검증했습니다.
- 실기기·OEM별 런처, TalkBack 수동 조작, 사용자 지정 낮은 불투명도에서 모든 배경화면의 대비는 추가 확인이 필요합니다.
- 실제 NEIS 인증과 원격 Worker/Pages 배포는 이번 UI 작업의 검증 범위가 아닙니다. 기존 미배포 조건은 [데이터 배포 안내](DEPLOY-DATA.md)를 따릅니다.

## 참고한 공식 문서

- [Material 3 릴리스/API 호환성](https://developer.android.com/jetpack/androidx/releases/compose-material3)
- [Compose 네이티브 하단 시트](https://developer.android.com/develop/ui/compose/components/bottom-sheets)
- [기본 접근성·터치 영역](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
- [Adaptive icon 안전 영역·단색 레이어](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)
