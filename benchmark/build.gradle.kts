import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Step 8 Microbenchmark (androidx.benchmark). 측정 전용 모듈이며 배포하지 않는다.
 * - SDK는 배포된 AAR(Maven 좌표)로 받는다. SDK 소스·공개 API·런타임 의존성은 바꾸지 않는다.
 * - Microbenchmark는 디버그 불가 빌드에서 돌아야 하므로 테스트 빌드 타입을 release로 둔다.
 * 실행: ./gradlew :benchmark:connectedReleaseAndroidTest (실기기)
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.dduddupark.minianalytics.benchmark"
    compileSdk = 36

    defaultConfig {
        // androidx.benchmark 1.5.0의 요구사항(24 이상). 측정 모듈만 해당하며 SDK의 minSdk(23)는 그대로다.
        minSdk = 24
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }

    testBuildType = "release"

    buildTypes {
        release {
            isMinifyEnabled = false
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
    androidTestImplementation("io.github.dduddupark:mini-analytics:0.1.0-SNAPSHOT")
    androidTestImplementation(libs.androidx.benchmark.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
