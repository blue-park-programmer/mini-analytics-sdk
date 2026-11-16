import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Step 8 벤치마크 호스트 앱 (측정 전용, 배포하지 않음).
 * - SDK는 sample과 같이 **배포된 AAR(Maven 좌표)** 으로 받는다. SDK 소스·동작은 바꾸지 않는다.
 *   먼저 `./gradlew :mini-analytics:publishReleasePublicationToLocalBuildRepository`를 실행한다.
 * - debug(디버그 가능)와 release(R8, profileable)를 같은 시나리오로 비교한다. 판정에는 release만 쓴다.
 * - 측정 실행: tools/benchmark/run_host.py (호출 지연), tools/benchmark/run_store.py (저장)
 * - 저장 측정의 독립 검증은 로컬 검증 서버(tools/device-verification/verify.py)로 실제 업로드해서 한다.
 *   그 서버 주소는 `-PbenchEndpoint=https://<Mac의 LAN IP>:8443/v1/batch`로 지정하고, sample과 같이
 *   그 호스트 하나에만 테스트 CA를 신뢰한다. 지정하지 않으면 업로드가 즉시 실패하는 주소를 쓴다.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val benchEndpoint: String = providers.gradleProperty("benchEndpoint").getOrElse("https://127.0.0.1:9/v1/batch")
val benchHost: String = run {
    val uri = URI(benchEndpoint)
    require(uri.scheme == "https") { "benchEndpoint는 https여야 합니다: $benchEndpoint" }
    val host = requireNotNull(uri.host) { "benchEndpoint에 호스트가 없습니다" }
    val parts = host.split(".").mapNotNull { it.toIntOrNull() }
    val local = host == "localhost" || (parts.size == 4 && (parts[0] == 127 || parts[0] == 10 ||
        (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 192 && parts[1] == 168)))
    require(local) { "benchEndpoint 호스트($host)는 loopback·localhost·사설 IPv4여야 합니다" }
    host
}

/** 테스트 CA(sample의 mock_ca.pem 복사본)를 [host] 하나에만 신뢰하는 리소스를 만든다. */
abstract class GenerateBenchTrust : DefaultTask() {
    @get:Input
    abstract val host: Property<String>

    @get:InputFile
    abstract val caFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val raw = outputDir.dir("raw").get().asFile.apply { mkdirs() }
        caFile.get().asFile.copyTo(raw.resolve("bench_ca.pem"), overwrite = true)
        val xml = outputDir.file("xml/bench_network_security_config.xml").get().asFile
        xml.parentFile.mkdirs()
        xml.writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- 생성 파일 (benchmark-host/build.gradle.kts). 측정 전용. -->
            |<network-security-config>
            |    <base-config cleartextTrafficPermitted="false">
            |        <trust-anchors><certificates src="system" /></trust-anchors>
            |    </base-config>
            |    <domain-config cleartextTrafficPermitted="false">
            |        <domain includeSubdomains="false">${host.get()}</domain>
            |        <trust-anchors><certificates src="@raw/bench_ca" /></trust-anchors>
            |    </domain-config>
            |</network-security-config>
            |""".trimMargin()
        )
    }
}

android {
    namespace = "io.github.dduddupark.minianalytics.benchhost"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.dduddupark.minianalytics.benchhost"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "BENCH_ENDPOINT", "\"$benchEndpoint\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // debug와 release를 함께 설치해 측정 순서를 섞을 수 있게 한다 (열 상태 편향 감소)
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
        val task = tasks.register<GenerateBenchTrust>(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}BenchTrust"
        ) {
            host.set(benchHost)
            caFile.set(rootProject.layout.projectDirectory.file("sample/src/main/res/raw/mock_ca.pem"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(task, GenerateBenchTrust::outputDir)
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
