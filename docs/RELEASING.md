# 공개 릴리스와 정식 서명

공식 저장소는 [Mangom72/hannun-meal](https://github.com/Mangom72/hannun-meal)이며 공개 APK와 `update.json`을 Releases에 배포합니다. 앱은 로그인 없이 최신 정식 릴리스를 확인합니다. 일반 사용자는 저장소·토큰·데이터 서버 주소를 입력하지 않습니다.

## 기존 서명키 보존

`.signing/release.p12`와 `.signing/signing.properties`는 Git 제외·소유자 전용 로컬 파일입니다. 기존 정식 앱과 같은 키를 계속 사용합니다. 이미 배포한 앱의 키를 새로 생성하면 업데이트할 수 없습니다. 두 파일은 별도 비공개 백업에 보관하십시오.

공개 저장소에는 소스와 APK의 공개 인증서만 포함하며, 개인키·암호·인증 토큰은 포함하지 않습니다. GitHub Actions는 네 가지 Repository Secrets로 기존 키를 전달받습니다.

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

```sh
python3 scripts/setup_signing.py --repo Mangom72/hannun-meal --upload --allow-public
```

이 명령은 기존 키를 그대로 보존하고 표준입력으로 Secrets에 전달합니다. 공개 저장소 대상은 명시적인 `--allow-public`이 있어야 허용합니다. 값은 명령 인자·콘솔·소스에 넣지 않습니다. 키가 없는 이미 릴리스된 저장소에서는 재생성을 거부합니다.

## 데이터 설정

`backend.properties.example`은 공식 서비스의 공개 주소만 담으며 일반 빌드의 기본값입니다. 개발용 변경은 Git 제외 `backend.properties` 또는 같은 이름의 환경 변수로 지정할 수 있습니다. 공식 CI의 공개 Variables도 같은 주소를 사용합니다.

- `MEAL_API_BASE_URL`
- `SCHOOL_DIRECTORY_BASE_URL`

NEIS 키는 Worker와 학교 자료 저장소 Actions의 `NEIS_API_KEY` Secret에만 저장합니다. Android 릴리스 작업에는 NEIS 키가 필요하지 않습니다. 학교 목록은 기존 `hannun-meal-schools` Pages가 담당하며 앱 저장소의 `SCHOOL_DIRECTORY_EXTERNAL=true`는 중복 배포를 차단합니다.

## 소스 업로드와 릴리스

```sh
python3 scripts/configure_github.py --repo Mangom72/hannun-meal --allow-public --push
```

소스 allowlist만 추가하고 기존 staged 변경·다른 브랜치·다른 origin을 덮어쓰지 않습니다. `.signing`, 로컬 설정, 빌드 결과, 작업 인계·운영 로그는 제외합니다. 공개 전 비밀정보 검사와 diff 검토를 수행하십시오.

이후 릴리스는 버전과 versionCode를 함께 증가시킵니다.

```sh
python3 scripts/bump_version.py 1.2.2
# CHANGELOG.md 최상단에 해당 버전 변경사항 작성
./gradlew testDebugUnitTest lintDebug
python3 -m unittest discover -s scripts/tests -v
git add version.properties CHANGELOG.md app scripts .github docs
# 변경 내용을 검토한 뒤 커밋하고 main에 push
# main에서 수동 배포하거나 버전에 일치하는 v* 태그 사용
gh workflow run release.yml --repo Mangom72/hannun-meal --ref main
```

`Signed release` workflow는 공식 저장소와 신뢰된 ref에서 테스트 → 기존 키 복원 → 서명 빌드 → APK/메타데이터 검증 → draft 업로드 → 정식 공개 순으로 동작합니다. 서명 Secrets는 PR CI에서 사용하지 않으며 임시 키는 항상 삭제합니다. 같은 버전의 기존 릴리스는 덮어쓰지 않습니다.

배포 자산은 `hannun-meal.apk`, `update.json`입니다. 업데이트 기록에는 해당 버전의 변경사항만 포함합니다. 공개 페이지의 APK를 한 번 설치하면 이후에는 앱의 자동 확인·다운로드를 사용할 수 있습니다. 이전 버전이 인증 설정을 요구하는 경우 이번 공개 APK로 먼저 수동 업데이트하십시오.

## 설치 검증

앱은 최신 정식 릴리스의 숫자 asset ID만 사용하며 draft/prerelease·이전 버전·임의 다운로드 URL을 거부합니다. 파일 크기·SHA-256·패키지·버전·SDK·현재 설치 앱의 서명을 확인하고 설치 직전에도 다시 검사합니다. API와 허용된 HTTPS GitHub CDN에 인증 헤더를 보내지 않습니다. 실패가 이전에 검증된 파일이나 급식 캐시를 삭제하지 않습니다.

자동 확인은 WorkManager 12시간 주기와 Wi-Fi/배터리 조건을 따릅니다. 최종 설치는 Android 시스템 화면에서 사용자가 확인합니다. 실제 휴대폰의 제조사 절전 정책과 설치 허용 상태는 별도 확인이 필요합니다.
