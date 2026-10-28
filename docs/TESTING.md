# 검증 기록

최신 UI 검증: [2026-10-28 UI/UX 개편](UI-REFRESH-2026-10-28.md). Android 100개·Python 20개, lint 오류 0/경고 59, debug 빌드, 실제 에뮬레이터의 밝음/어두움·큰 글자·위젯 테마 전환·아이콘 렌더를 확인했습니다. 정식 릴리스는 생성하지 않았습니다.

데이터 계층 검증은 [1.2.0 데이터 계층 이전 검증](TESTING-1.2.0.md)입니다. Android 92개·Python 20개·Worker 14개 검사, 기존 서명 APK, 실제 에뮬레이터의 월 요청 재사용·오프라인 보존 결과와 원격 배포 한계를 기록했습니다.

아래는 1.1.0의 역사적 서명·자동 업데이트 검증입니다.

최신 로컬 재검증: [2026-10-27 결과](TESTING-LOCAL-2026-10-27.md). 아래는 이전 클라우드 실행 기록이며 APK 해시·lint 경고 수·기기 검증 범위는 최신 문서를 우선합니다.

검증일: 2026-10-26. 원격 GitHub 설정과 실제 Android 설치 화면 검증은 아래 한계에 구분했습니다. [초기 1.0.0 기록](TESTING-1.0.0.md)은 별도 보존합니다.

## 실행한 명령과 결과

아래 Gradle 명령은 이 실행 환경의 JDK 17과 프록시 옵션을 사용했습니다. 일반 PC에서는 `./gradlew` 뒤의 task만 사용하면 됩니다.

| 실제 검사 | 결과 |
| --- | --- |
| testDebugUnitTest lintDebug assembleDebug | **BUILD SUCCESSFUL in 2m 56s** |
| Android JUnit/Robolectric | **82개 통과 · 실패/오류/건너뜀 0** |
| lintDebug | **오류 0 · 경고 59**, task 성공 |
| assembleRelease | **BUILD SUCCESSFUL in 2m 11s**, 정식 PKCS12 키로 서명 |
| python3 -m unittest discover -s scripts/tests -v | **7개 통과** |
| actionlint 1.7.12, 두 workflow 파일 | 종료 코드 0 |
| create_update_manifest.py | 실제 APK 검증 후 메타데이터 생성 성공 |
| apksigner verify --verbose --print-certs | 종료 코드 0 · v2 서명 검증 성공 · RSA 3072 |
| zipalign -c -P 16 4 | 종료 코드 0 |
| 자격 증명을 모두 빈 값으로 주입한 validateReleaseCredentials | **예상대로 종료 코드 1**, unsigned 릴리스 금지 오류 |
| 같은 빈 자격 증명으로 assembleDebug | **BUILD SUCCESSFUL in 22s**, 40 tasks up-to-date |
| GitHub createRepository | **Resource not accessible by integration**, 원격 생성 미완료 |

실행 로그: [단위 테스트·lint·debug](verification/updater-gradle.txt), [정식 빌드](verification/release-gradle.txt), [서명 없는 릴리스 차단](verification/signing-required.txt), [Python 테스트](verification/release-tool-tests.txt). [기계 판독 결과](verification/updater-results.json).

## 추가한 테스트

기존 61개 검사에 다음 21개를 추가했습니다.

- UpdateRulesTest 6개: 저장소 경로/URL 주입 차단, 지원 SDK, 인증서, 패키지·크기·해시 형식, 손상·잘린 파일
- GitHubTransportTest 6개: 인증 헤더와 endpoint, 401/404/429/503 구분, CDN으로 토큰 미전달, 허용되지 않은 redirect, 크기 초과·잘림, manifest 크기·파싱
- UpdatePersistenceTest 8개: 디스크에 토큰 평문 없음, 토큰 유지/삭제, 다운로드 후 검증·캐시 재사용, 변조/다른 서명 차단과 임시 파일 삭제, 인증 실패 시 준비된 파일 보존, 다운그레이드 금지, 토큰 없는 요청 0건, 캐시 회수 후 재다운로드 가능 상태
- WidgetContractTest 1개 추가: FileProvider의 비공개 업데이트 캐시만 공유 가능하며 DataStore 파일 공유 불가

Python 테스트는 버전 증가·잘못된 버전·오버플로 3개와, 기존 키 보존·부분 키 파일 중단·이미 배포한 저장소에 새 키 생성 금지·Secrets는 표준입력으로만 전달하고 콘솔/인수에 비밀 없음 4개입니다.

실제 Android Keystore의 하드웨어 동작을 Robolectric에서 재현했다고 주장하지 않습니다. 저장소 테스트에는 AES-GCM 테스트 암호기를 주입하고, 앱에서는 Android Keystore 구현을 사용합니다. 실제 APK의 서명과 메타데이터는 Android SDK 도구로 검사했습니다.

## 생성한 정식 APK

- 버전 1.1.0, versionCode 2, package `kr.han.meal`, minSdk 26
- 원본: `app/build/outputs/apk/release/app-release.apk`
- 배포용 동일 파일: `artifacts/release/hannun-meal.apk`
- 크기: **9,616,364 bytes**
- APK SHA-256: `4c0082a65349d9cfe873b4a65cae9852730e5d84f3fa68e554d082893f2576ea`
- 서명 인증서 SHA-256: `9851c533f0375ec5e3ef6cb588d1d2378b4a0adaa3f4292f1a372f9d594316b7`

인증서 지문은 공개 정보이며 개인 키나 암호가 아닙니다. 키와 암호 파일은 `.signing/`에만 있으며 Git/소스 ZIP에서 제외합니다. 정식 APK와 debug APK는 서명이 다르므로 처음 전환할 때 재설치가 필요합니다.

## 수정한 실행 문제

- 초기 실행 중 WorkManager가 준비되기 전 접근할 수 있는 문제를 Application의 공식 `Configuration.Provider` 지연 초기화로 수정했습니다. 초기화 실패를 숨기는 대신 main 앱·백그라운드 공통 경로를 고쳤습니다.
- OS가 APK 캐시를 회수한 경우 준비 상태를 계속 표시하지 않도록 수정했습니다. 설치 직전 재검증이 실패하면 준비 상태를 해제하고 다시 받을 수 있게 합니다.
- GitHub 관리자 권한을 우회하지 않았습니다. 원격 생성 거부를 확인한 뒤 소스 업로드·Secrets·릴리스 작업은 실행하지 않았습니다. 본인 PC에서 실행할 재사용 bootstrap 명령을 제공합니다.

## 원격 및 기기 검증 한계

`Mangom72/hannun-meal` 생성/접근 권한이 없으므로 GitHub Actions의 실제 runner 실행, Secrets 등록, 비공개 Release 발행 및 개인 읽기 토큰의 실서비스 접근은 아직 미검증입니다. 로컬 Actions 문법, 서명 APK 생성, 프로토콜·인증 경계 테스트는 완료했습니다.

실기기·에뮬레이터가 없어 Android Keystore 실제 보관, 설치 허용 화면에서 돌아오는 흐름, 시스템 패키지 업데이트 후 데이터 유지, 제조사별 12시간 작업 지연은 기기 검증이 필요합니다. 앱에 무인 설치를 약속하거나 원격 배포가 완료된 것처럼 표시하지 않습니다.

## 기기 확인 절차

1. 정식 APK 설치 후 학교/NEIS 키 설정 및 기존 위젯 기능을 확인합니다.
2. 비공개 저장소 Contents 읽기 전용 토큰을 앱 업데이트 설정에 저장합니다. 토큰 없이 확인하면 네트워크 호출 대신 설정 안내가 표시됩니다.
3. 같은 키로 versionCode가 더 높은 릴리스를 배포하고 수동 확인·다운로드·설치를 실행합니다.
4. 설치 허용 설정을 켜고 돌아왔을 때 시스템 설치 화면이 열리는지 확인합니다. 이후 학교·시간 규칙·위젯 ID가 유지되어야 합니다.
5. 토큰 만료, 연결 끊김, 다른 서명/손상 파일, 앱 캐시 삭제에서 설치되지 않고 복구 안내가 표시되는지 확인합니다.
6. 자동 확인을 끄거나 토큰을 삭제하면 periodic 작업이 취소되는지 확인합니다. 절전 상태에서 정각 실행을 기대하지 않습니다.

전체 배포·백업 명령은 [RELEASING.md](RELEASING.md)를 참조하세요.
