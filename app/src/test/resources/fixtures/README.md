# 테스트 응답 자료

- `schools.json`, `meals.json`: 필드 누락·중복 이름·HTML·알레르기 검증을 위한 가상 테스트 응답. 런타임에 포함하지 않습니다.
- `empty.json`, `auth.json`: NEIS RESULT envelope의 빈 결과/인증 실패 구조를 재현합니다.
- `neis-real-20250901.json`: 2026-10-26에 공식 공개 endpoint에서 캡처한 **2025-09-01~05 가락고등학교(B10:7010057)** 응답입니다. 현재 급식이나 데모로 표시하지 않으며, 파서 회귀 테스트 및 날짜를 고정한 네이티브 위젯 렌더 테스트에서만 사용합니다.

캡처 URL(인증키 없음):
`https://open.neis.go.kr/hub/mealServiceDietInfo?Type=json&pIndex=1&pSize=5&ATPT_OFCDC_SC_CODE=B10&SD_SCHUL_CODE=7010057&MLSV_FROM_YMD=20250901&MLSV_TO_YMD=20250905`

자료 제공: 교육부 NEIS. 앱은 실제 사용 시 개인 인증키와 선택한 학교·날짜를 이용해 별도로 조회합니다.
