package com.andrerinas.openheadunit.ssl

import android.content.Context
import android.os.Build
import com.andrerinas.openheadunit.utils.AppLog
import java.net.InetAddress
import java.net.Socket
import java.security.Provider
import java.security.Security
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object ConscryptInitializer {
    @Volatile private var initialized = false
    @Volatile private var conscryptAvailable = false

    @Synchronized
    fun initialize(): Boolean {
        if (initialized) return conscryptAvailable
        initialized = true

        try {
            val provider = newProvider()

            // Insert at position 1 (highest priority)
            val result = Security.insertProviderAt(provider, 1)

            // Check if installation succeeded or if already installed
            conscryptAvailable = result != -1 || Security.getProvider("Conscrypt") != null

            if (conscryptAvailable) {
                AppLog.i("Conscrypt installed as security provider (position: %d)", result)
            }
        } catch (e: ClassNotFoundException) {
            AppLog.e("Conscrypt library not found - TLS 1.2 may not work on Android < 21", e)
            conscryptAvailable = false
        } catch (e: Exception) {
            AppLog.e("Failed to initialize Conscrypt", e)
            conscryptAvailable = false
        }

        return conscryptAvailable
    }

    // What Conscrypt.newProvider() builds, without that class: its static block reads a resource, and
    // Dalvik answers by indexing the whole APK on the main thread at every launch.
    internal fun newProvider(): Provider =
        Class.forName("org.conscrypt.OpenSSLProvider").getConstructor().newInstance() as Provider

    fun isAvailable(): Boolean = conscryptAvailable

    fun isNeededForTls12(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP

    fun getProviderName(): String? = if (conscryptAvailable) "Conscrypt" else null

    @Volatile private var httpsFactory: SSLSocketFactory? = null

    // Registering the provider does not move HttpsURLConnection off the platform stack, whose hello
    // below API 21 offers no TLS 1.2, and its root store lacks the roots in BundledRootTrustManager.
    @Synchronized
    fun httpsSocketFactory(context: Context): SSLSocketFactory? {
        if (!isNeededForTls12() || !conscryptAvailable) return null
        httpsFactory?.let { return it }
        return try {
            val context = SSLContext.getInstance("TLS", "Conscrypt").apply {
                init(null, arrayOf(BundledRootTrustManager.create(context)), null)
            }
            ModernTlsSocketFactory(context.socketFactory).also { httpsFactory = it }
        } catch (e: Exception) {
            AppLog.w("ConscryptInitializer: no bundled HTTPS socket factory: %s", e.toString())
            null
        }
    }

    // The platform okhttp on API 19 may narrow the protocols it is handed, so re-enable 1.2 and 1.3.
    private class ModernTlsSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            enable(delegate.createSocket(s, host, port, autoClose))
        override fun createSocket(host: String, port: Int): Socket = enable(delegate.createSocket(host, port))
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            enable(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress, port: Int): Socket = enable(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            enable(delegate.createSocket(address, port, localAddress, localPort))

        private fun enable(socket: Socket): Socket {
            if (socket is SSLSocket) {
                val modern = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
                if (modern.isNotEmpty()) socket.enabledProtocols = modern.toTypedArray()
            }
            return socket
        }
    }
}
