# mini-analytics-sdk

## Language

- 모든 질문, 설명, 문서 서술은 **한국어**로 작성한다.
- 코드, 클래스명, 변수명, 함수명, API 이름, 파일 경로, CLI 명령은 **영어**를 유지한다.

## Build

- 빌드 + 단위 테스트: `./gradlew :mini-analytics:assembleRelease :mini-analytics:testDebugUnitTest`
- `local.properties`에 `sdk.dir`이 필요하다 (`ANDROID_HOME` 미설정 환경). 이 파일은 git에 포함하지 않는다.
- `org.json`을 쓰는 테스트는 Robolectric으로, 순수 Kotlin 로직은 일반 JUnit으로 테스트한다. 테스트 전용 JSON 라이브러리는 추가하지 않는다.
- 설계: `docs/design/v1-design.md`, 구현 계획: `docs/design/v1-implementation-plan.md`. 단계마다 사용자 승인을 받은 뒤 다음 단계로 진행한다.

## Agent skills

### Issue tracker

Issues live as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five canonical labels (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
