# 0단계 보고서: 프로젝트 골격

- 작성일: 2026-11-04
- 계획: [`v1-implementation-plan.md`](./v1-implementation-plan.md) 0단계

## 선택한 버전과 근거

원칙: 최신 버전 자체보다 **빌드 안정성과 호스트 앱 호환성**을 우선합니다 (사용자 지시).

| 구성 요소 | 선택 | 확인 시점의 최신 안정 버전 | 근거 |
|---|---|---|---|
| Gradle (wrapper) | **8.14.3** | 9.8.2 / 8.14.6 | AGP 8.13이 요구하는 Gradle 8.13 이상을 만족함. 8.x 계열의 성숙한 패치(2025-07)이고 로컬에 이미 설치되어 있음. Gradle 9는 AGP 9와 함께 쓰는 경우가 많아서 보류함. 배포본 SHA-256(`bd711022…5f3531`)을 `distributionSha256Sum`으로 고정함 |
| AGP | **8.13.2** | 9.5.0 | AGP 8 계열의 마지막 minor 버전의 최신 패치. AGP 9는 Kotlin 내장 지원, DSL 변경 등 큰 변화가 있는 major 버전이라 안정성을 우선해서 제외함. `compileSdk 36`을 지원함 |
| Kotlin (KGP) | **2.2.21** | 2.4.21 | AGP 8.13과 함께 널리 쓰이는 조합. 호스트 앱 호환성을 생각하면 최신 2.4보다 한두 단계 낮은 버전이 유리함. 지원할 호스트 Kotlin 버전 범위는 7단계에서 실제로 검증함 (A-5) |
| kotlinx-coroutines | **1.10.2** | 1.11.0 | 1.10 계열의 최신 패치이며 Kotlin 2.2와 호환됨. 1.11은 최근 버전이라 호스트 앱 생태계에 충분히 퍼지기 전임. **아직 의존성에 추가하지 않음** (3단계에서 추가) |
| Robolectric | **4.16.1** | 4.17 | SDK 36까지 지원하는 4.16 계열의 최신 패치. 4.17은 최근 버전이라 보류함 |
| JUnit | **4.13.2** | — | Robolectric runner와 함께 쓰는 표준 |

## 빌드 설정
| 항목 | 값 | 확인 방법 |
|---|---|---|
| `minSdk` / `compileSdk` | 23 / 36 | AAR manifest: `minSdkVersion="23"` |
| `jvmTarget` / Java `source`·`target` | 11 | `classes.jar`의 class 파일 major version 55 (= Java 11) |
| `explicitApi()` | 적용 | 가시성을 쓰지 않은 확인용 선언을 넣으면 `Visibility must be specified in explicit API mode` 오류가 남. 확인 후 그 파일은 삭제함 |
| AAR metadata | `minCompileSdk=1`, `minAndroidGradlePluginVersion=1.0.0`, `coreLibraryDesugaringEnabled=false` | 호스트 앱의 compileSdk, AGP, desugaring에 대한 제약 없음 |
| Robolectric 기본 SDK | 34 (`src/test/resources/robolectric.properties`) | Robolectric에서 SDK 35 이상을 쓰려면 테스트를 JDK 21로 실행해야 함. "JDK 17 이상" 조건을 지키기 위해 34로 고정함 |
| `android.useAndroidX` | `true` | Robolectric이 테스트 classpath에 `androidx.test`를 가져오므로 필요함. **SDK 런타임 의존성에는 영향 없음** |

## 빌드와 테스트 결과
| 명령 | JDK | 결과 |
|---|---|---|
| `./gradlew :mini-analytics:assembleRelease :mini-analytics:testDebugUnitTest` | Temurin 21.0.12 | BUILD SUCCESSFUL |
| `./gradlew clean :mini-analytics:assembleRelease :mini-analytics:testDebugUnitTest` | JBR 17.0.14 | BUILD SUCCESSFUL |

| 테스트 | 종류 | 결과 |
|---|---|---|
| `MiniAnalyticsSmokeTest` (1) | 일반 JUnit | 통과 |
| `RobolectricSetupTest.orgJsonWorksUnderRobolectric` | Robolectric | 통과 (`org.json`이 실제로 동작함) |
| `RobolectricSetupTest.runsOnConfiguredSdk` | Robolectric | 통과 |

릴리스 런타임 classpath:
```
releaseRuntimeClasspath
\--- org.jetbrains.kotlin:kotlin-stdlib:2.2.21
     \--- org.jetbrains:annotations:13.0
```

## 남은 위험
| ID | 위험 | 대응 시점 |
|---|---|---|
| S0-1 | **`kotlin-stdlib 2.2.21`이 전이 의존성으로 노출됨.** 이보다 낮은 stdlib를 쓰는 호스트 앱은 Gradle의 버전 해결 규칙 때문에 2.2.21로 올라감 | 7단계 (A-5). KGP의 `coreLibrariesVersion`으로 stdlib 의존 버전을 낮추는 방법과 Kotlin Metadata 호환성을 함께 검증 |
| S0-2 | Robolectric을 SDK 34로만 실행함. SDK 23(최소)과 36(최신)에서의 동작 차이, 특히 SQLite 버전 차이를 아직 검증하지 않음 | 2단계 SQLite 테스트에서 `@Config(sdk = [...])`로 SDK별 실행을 검토. SDK 35 이상은 JDK 21이 필요함 |
| S0-3 | Robolectric 때문에 테스트 classpath에 AndroidX가 들어옴 | 테스트 전용이라 배포물에는 영향 없음. 7단계 POM 검증에서 다시 확인 |
| S0-4 | 로컬 Gradle daemon과 configuration cache가 JDK마다 따로 생김 | 문서화. CI를 도입하면 JDK를 고정 |
| S0-5 | 패키지명 `io.github.dduddupark.minianalytics`와 group `io.github.dduddupark`는 임시로 정한 값. Maven Central에 배포하려면 도메인 검증이 필요함 | 배포 전 확정 (v1 범위는 로컬 Maven) |
