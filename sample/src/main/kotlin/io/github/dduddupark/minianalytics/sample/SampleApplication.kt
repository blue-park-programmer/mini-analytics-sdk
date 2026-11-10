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

class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT < 24) trustLocalTestServerOnApi23()

        // 통합 가이드 G-1: Application.onCreate()에서 초기화한다.
        // StrictMode를 SDK 초기화보다 먼저 켠다: 메인 스레드의 디스크·네트워크 I/O가 있으면 앱이 종료된다.
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .penaltyDeath()
                .build(),
        )
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
     * API 23은 network_security_config를 지원하지 않으므로, 검증용 인증서를 신뢰하는 기본 SSLSocketFactory를
     * 설정한다. 샘플 앱의 검증 환경 전용이며 SDK 코드와 무관하다 (SDK는 기본 HttpsURLConnection 설정을 따른다).
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
        val context = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
    }

    companion object {
        const val TAG = "MiniAnalyticsSample"
    }
}
