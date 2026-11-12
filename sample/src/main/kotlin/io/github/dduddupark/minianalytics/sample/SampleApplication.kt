package io.github.dduddupark.minianalytics.sample

import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.util.Log
import io.github.dduddupark.minianalytics.Configuration
import io.github.dduddupark.minianalytics.MiniAnalytics
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT < 24) trustLocalTestServerOnApi23()

        // 통합 가이드 G-1: Application.onCreate()에서 초기화한다.
        // StrictMode를 SDK 초기화보다 먼저 켠다 (메인 스레드의 디스크·네트워크 I/O 검증).
        installStrictMode()
        val configuration = Configuration.Builder("pk_test", BuildConfig.ENDPOINT)
            .flushAt(1)
            .flushIntervalMillis(5_000)
            // 검증 서버(tools/device-verification)는 서버 계약 3번(invalid_event)을 구현하므로 명시적으로 신뢰한다.
            // 계약을 확인하지 않은 운영 서버라면 기본값(false)을 유지한다.
            .trustInvalidEventResponses(true)
            .build()
        val result = MiniAnalytics.initialize(this, configuration)
        Log.i(TAG, "initialize -> $result (sdk=${Build.VERSION.SDK_INT})")
    }

    /**
     * 메인 스레드 I/O 검증 (물리 기기 검증 7장).
     *
     * - API 28 이상: 앱을 종료하지 않고 위반마다 **전체 스택**을 `MiniAnalyticsStrict` 태그로 기록한다.
     *   제조사 프레임워크(예: Samsung One UI의 `IdsController`)는 Activity resume 중 메인 스레드에서 디스크를
     *   읽는데, `penaltyDeath`를 쓰면 SDK와 무관한 이 위반 때문에 앱이 종료된다. 위반의 출처(SDK·sample /
     *   프레임워크 / 미분류) 판정은 검증 도구(tools/device-verification/verify.py)가 스택으로 한다.
     * - API 23~27: `penaltyListener`가 없으므로 기존처럼 `penaltyDeath`(어떤 위반이든 종료)를 유지한다.
     */
    private fun installStrictMode() {
        val builder = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
        if (Build.VERSION.SDK_INT >= 28) {
            val counter = AtomicInteger()
            builder.penaltyListener(Executors.newSingleThreadExecutor()) { violation ->
                val id = counter.incrementAndGet()
                Log.w(STRICT_TAG, "#$id begin ${violation.javaClass.name}")
                for (frame in violation.stackTrace) Log.w(STRICT_TAG, "#$id at $frame")
                Log.w(STRICT_TAG, "#$id end")
            }
        } else {
            builder.penaltyLog().penaltyDeath()
        }
        StrictMode.setThreadPolicy(builder.build())
    }

    /**
     * API 23은 network_security_config를 지원하지 않으므로, 기본 SSLSocketFactory를 [HostScopedSSLSocketFactory]로
     * 바꿔서 검증 서버 호스트(`BuildConfig.TEST_SERVER_HOST`)로 가는 연결에만 테스트 CA를 적용한다.
     * 다른 호스트는 원래의 시스템 기본값을 그대로 쓴다. 샘플 앱의 검증 환경 전용이며 SDK 코드와 무관하다
     * (SDK는 기본 HttpsURLConnection 설정을 따른다).
     */
    private fun trustLocalTestServerOnApi23() {
        val certificate = resources.openRawResource(R.raw.mock_ca).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("mock", certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        val testContext = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        HttpsURLConnection.setDefaultSSLSocketFactory(
            HostScopedSSLSocketFactory(
                testHost = BuildConfig.TEST_SERVER_HOST,
                testFactory = testContext.socketFactory,
                systemFactory = HttpsURLConnection.getDefaultSSLSocketFactory(),
            )
        )
    }

    companion object {
        const val TAG = "MiniAnalyticsSample"
        const val STRICT_TAG = "MiniAnalyticsStrict"
    }
}
