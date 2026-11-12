import java.net.URI
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

/*
 * 검증 서버 주소. 기본값은 에뮬레이터에서 본 호스트 PC(10.0.2.2). 물리 기기에서는 빌드할 때 바꾼다:
 *   adb reverse 방식: -PsampleEndpoint=https://127.0.0.1:8443/v1/batch
 *   LAN 방식:        -PsampleEndpoint=https://<Mac의 LAN IP>:8443/v1/batch
 *
 * 테스트 CA(res/raw/mock_ca.pem)의 신뢰 범위 (sample 전용, SDK의 인증서 검증 정책과 무관):
 * - 이 endpoint의 호스트 **하나만** 테스트 CA를 신뢰한다. 그 밖의 모든 주소는 시스템 CA만 신뢰한다.
 * - https만 허용하고, 로컬 테스트 주소(loopback, localhost, 사설 IPv4 10/8·172.16/12·192.168/16)가 아니면
 *   빌드를 실패시킨다. 공개 주소에 테스트 CA를 신뢰하는 빌드가 만들어지지 않게 하기 위해서다.
 */
val sampleEndpoint: String = providers.gradleProperty("sampleEndpoint").getOrElse("https://10.0.2.2:8443/v1/batch")
val testServerHost: String = run {
    val uri = URI(sampleEndpoint)
    require(uri.scheme == "https") { "sampleEndpoint는 https여야 합니다: $sampleEndpoint" }
    val host = requireNotNull(uri.host) { "sampleEndpoint에 호스트가 없습니다: $sampleEndpoint" }
    require(isLocalTestHost(host)) {
        "sampleEndpoint 호스트($host)는 loopback·localhost·사설 IPv4여야 합니다 (테스트 CA 신뢰 범위 제한)"
    }
    host
}

fun isLocalTestHost(host: String): Boolean {
    if (host == "localhost") return true
    val parts = host.split(".").map { it.toIntOrNull() ?: return false }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    val (a, b) = parts
    return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}

/** 테스트 CA를 [host]에만 신뢰하는 network_security_config를 만든다 (API 24+. API 23은 SampleApplication). */
abstract class GenerateTestNetworkSecurityConfig : DefaultTask() {
    @get:Input
    abstract val host: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = outputDir.file("xml/network_security_config.xml").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- 생성 파일 (sample/build.gradle.kts). 수정하지 마세요.
            |     테스트 CA(@raw/mock_ca)는 검증 서버 호스트 하나에만 신뢰한다. 그 밖은 시스템 CA만. 평문 HTTP 금지. -->
            |<network-security-config>
            |    <base-config cleartextTrafficPermitted="false">
            |        <trust-anchors>
            |            <certificates src="system" />
            |        </trust-anchors>
            |    </base-config>
            |    <domain-config cleartextTrafficPermitted="false">
            |        <domain includeSubdomains="false">${host.get()}</domain>
            |        <trust-anchors>
            |            <certificates src="@raw/mock_ca" />
            |        </trust-anchors>
            |    </domain-config>
            |</network-security-config>
            |""".trimMargin()
        )
    }
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
        // 로컬 검증 서버(tools/device-verification/verify.py) 주소와, 테스트 CA를 신뢰할 유일한 호스트.
        buildConfigField("String", "ENDPOINT", "\"$sampleEndpoint\"")
        buildConfigField("String", "TEST_SERVER_HOST", "\"$testServerHost\"")
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

androidComponents {
    onVariants { variant ->
        val task = tasks.register<GenerateTestNetworkSecurityConfig>(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}TestNetworkSecurityConfig"
        ) {
            host.set(testServerHost)
        }
        variant.sources.res?.addGeneratedSourceDirectory(task, GenerateTestNetworkSecurityConfig::outputDir)
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
