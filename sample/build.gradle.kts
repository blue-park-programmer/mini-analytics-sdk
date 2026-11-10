import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 소비 앱 (7단계 통합 검증).
 * - SDK를 프로젝트 의존성이 아니라 **배포된 AAR(Maven 좌표)** 으로 받는다.
 *   먼저 `./gradlew :mini-analytics:publishReleasePublicationToLocalBuildRepository`를 실행한다.
 * - release는 R8(minify)을 켠다. 디버그 키로 서명해서 설치할 수 있게 한다.
 * - 기기 검증 도구: tools/device-verification/verify.py
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.dduddupark.minianalytics.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.dduddupark.minianalytics.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // 에뮬레이터에서 호스트 PC의 로컬 검증 서버 (tools/device-verification/mock_server.py)
        buildConfigField("String", "ENDPOINT", "\"https://10.0.2.2:8443/v1/batch\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation("io.github.dduddupark:mini-analytics:0.1.0-SNAPSHOT")
}
