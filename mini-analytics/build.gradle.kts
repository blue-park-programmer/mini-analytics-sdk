import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = "io.github.dduddupark"
version = "0.1.0-SNAPSHOT"

android {
    namespace = "io.github.dduddupark.minianalytics"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        unitTests {
            // Robolectric이 Android 리소스와 manifest에 접근할 수 있게 한다.
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // 런타임 의존성은 이것 하나다 (설계 §10). -android 모듈은 쓰지 않는다.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    // HTTP 계약 테스트 전용 (설계 §10). 배포물(AAR/POM)에는 들어가지 않는다.
    testImplementation(libs.mockwebserver)

    // 실제 기기/에뮬레이터 테스트 (7단계). 배포물에는 들어가지 않는다.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.github.dduddupark"
            artifactId = "mini-analytics"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("mini-analytics")
                description.set("Minimal native Android analytics SDK (learning project)")
            }
        }
    }
    repositories {
        // 빌드 디렉터리 안의 로컬 저장소. 샘플 앱이 여기서 받는다. (mavenLocal()에도 배포할 수 있다.)
        maven {
            name = "localBuild"
            url = uri(rootProject.layout.buildDirectory.dir("local-maven"))
        }
    }
}

// =====================================================================================
// 공개 API 검증 (7단계 P7-4)
// release AAR의 공개 패키지(io.github.dduddupark.minianalytics, internal 제외) JVM 시그니처를 javap로 뽑아
// 기준 파일과 비교한다. 다르면 checkPublicApi가 실패한다 (check / build에 연결됨).
// 의도한 변경이면 ./gradlew :mini-analytics:updatePublicApi 로 기준 파일을 갱신하고 리뷰한다.
// =====================================================================================

abstract class PublicApiTask : DefaultTask() {
    @get:InputFile
    abstract val aar: RegularFileProperty

    @get:Internal
    abstract val reference: RegularFileProperty

    @get:Input
    abstract val update: Property<Boolean>

    @TaskAction
    fun run() {
        val dump = dumpPublicApi()
        val referenceFile = reference.get().asFile
        if (update.get()) {
            referenceFile.parentFile.mkdirs()
            referenceFile.writeText(dump)
            logger.lifecycle("Public API reference updated: $referenceFile")
            return
        }
        val expected = if (referenceFile.exists()) referenceFile.readText() else ""
        if (expected != dump) {
            val expectedLines = expected.lines().toSet()
            val actualLines = dump.lines().toSet()
            val removed = expected.lines().filter { it !in actualLines && it.isNotBlank() }.map { "- $it" }
            val added = dump.lines().filter { it !in expectedLines && it.isNotBlank() }.map { "+ $it" }
            throw GradleException(
                "Public API differs from $referenceFile.\n" + (removed + added).joinToString("\n") +
                    "\nIf this change is intended, run ./gradlew :mini-analytics:updatePublicApi and review the diff.",
            )
        }
        logger.lifecycle("Public API matches $referenceFile")
    }

    private fun dumpPublicApi(): String {
        val classesJar = File(temporaryDir, "classes.jar")
        ZipFile(aar.get().asFile).use { zip ->
            zip.getInputStream(zip.getEntry("classes.jar")).use { input ->
                classesJar.outputStream().use { input.copyTo(it) }
            }
        }
        val classNames = ZipFile(classesJar).use { zip ->
            zip.entries().asSequence().map { it.name }
                .filter { it.matches(Regex("io/github/dduddupark/minianalytics/[^/]+\\.class")) }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .sorted().toList()
        }
        val javap = File(System.getProperty("java.home"), "bin/javap").path
        val out = StringBuilder()
        out.append("# mini-analytics 공개 JVM API (release AAR, javap -public 기준)\n")
        out.append("# 생성: ./gradlew :mini-analytics:updatePublicApi. 이름이 바뀐 internal 멤버(\$mini_analytics_release)와 \$default 합성 메서드는 제외.\n\n")
        for (name in classNames) {
            val process = ProcessBuilder(javap, "-public", "-cp", classesJar.path, name).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "javap failed for $name: $text" }
            text.lines()
                .filter { it.isNotEmpty() }
                .filterNot { it.startsWith("Compiled from") || it.contains("\$mini_analytics") || it.contains("\$default(") }
                .forEach { out.append(it).append('\n') }
            out.append('\n')
        }
        return out.toString()
    }
}

val releaseAar = layout.buildDirectory.file("outputs/aar/mini-analytics-release.aar")

val checkPublicApi = tasks.register<PublicApiTask>("checkPublicApi") {
    group = "verification"
    description = "Fails if the public JVM API differs from docs/api/public-api.txt"
    dependsOn("bundleReleaseAar")
    aar.set(releaseAar)
    reference.set(rootProject.layout.projectDirectory.file("docs/api/public-api.txt"))
    update.set(false)
    outputs.upToDateWhen { false }
}

tasks.register<PublicApiTask>("updatePublicApi") {
    group = "verification"
    description = "Regenerates docs/api/public-api.txt from the release AAR"
    dependsOn("bundleReleaseAar")
    aar.set(releaseAar)
    reference.set(rootProject.layout.projectDirectory.file("docs/api/public-api.txt"))
    update.set(true)
    outputs.upToDateWhen { false }
}

tasks.named("check") { dependsOn(checkPublicApi) }
