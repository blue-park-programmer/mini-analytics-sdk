package io.github.dduddupark.minianalytics.sample

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * API 23 전용 (network_security_config 미지원). 테스트 CA를 [testHost] 하나에만 적용한다.
 *
 * 연결 대상 호스트가 [testHost]이면 [testFactory](테스트 CA만 신뢰), 그 밖이면 [systemFactory](시스템 CA)를 쓴다.
 * 호스트를 알 수 없는 `createSocket()`은 시스템 쪽으로 보낸다. sample 앱의 검증 환경 전용이며 SDK와 무관하다.
 */
internal class HostScopedSSLSocketFactory(
    private val testHost: String,
    private val testFactory: SSLSocketFactory,
    private val systemFactory: SSLSocketFactory,
) : SSLSocketFactory() {

    private fun pick(host: String?): SSLSocketFactory = if (host == testHost) testFactory else systemFactory

    override fun getDefaultCipherSuites(): Array<String> = systemFactory.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = systemFactory.supportedCipherSuites

    override fun createSocket(): Socket = systemFactory.createSocket()

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        pick(host).createSocket(socket, host, port, autoClose)

    override fun createSocket(host: String, port: Int): Socket = pick(host).createSocket(host, port)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        pick(host).createSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        pick(host.hostAddress).createSocket(host, port)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        pick(address.hostAddress).createSocket(address, port, localAddress, localPort)
}
