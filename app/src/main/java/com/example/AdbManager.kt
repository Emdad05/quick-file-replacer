package com.example

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

class AdbManager private constructor(private val context: Context) : AbsAdbConnectionManager() {

    interface ConnectionStateListener {
        fun onConnectionChanged(connected: Boolean)
    }

    private val listeners = CopyOnWriteArrayList<ConnectionStateListener>()
    private var cachedPrivateKey: PrivateKey? = null
    private var cachedCertificate: Certificate? = null
    private var mdnsDiscoverer: AdbMdns? = null

    companion object {
        private const val TAG = "QuickReplacer_ADB"
        private const val DELIMITER = "___QR_EXIT_DELIMITER___"

        @Volatile
        private var instance: AdbManager? = null

        fun getInstance(context: Context): AdbManager {
            return instance ?: synchronized(this) {
                instance ?: AdbManager(context.applicationContext).also {
                    instance = it
                    it.initKeys()
                }
            }
        }
    }

    fun addListener(listener: ConnectionStateListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            listener.onConnectionChanged(isConnected)
        }
    }

    fun removeListener(listener: ConnectionStateListener) {
        listeners.remove(listener)
    }

    private fun notifyStateChanged() {
        val connected = isConnected
        for (l in listeners) {
            try {
                l.onConnectionChanged(connected)
            } catch (e: Exception) {
                Log.e(TAG, "Listener error", e)
            }
        }
    }

    private fun initKeys() {
        try {
            val keyFile = File(context.filesDir, "adb_key.pk8")
            val certFile = File(context.filesDir, "adb_cert.der")

            if (keyFile.exists() && certFile.exists()) {
                val keyBytes = keyFile.readBytes()
                val kf = KeyFactory.getInstance("RSA")
                cachedPrivateKey = kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))

                val cf = CertificateFactory.getInstance("X.509")
                cachedCertificate = cf.generateCertificate(FileInputStream(certFile))
            } else {
                generateAndSaveKeys(keyFile, certFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize keys, regenerating", e)
            try {
                val keyFile = File(context.filesDir, "adb_key.pk8")
                val certFile = File(context.filesDir, "adb_cert.der")
                generateAndSaveKeys(keyFile, certFile)
            } catch (e2: Exception) {
                Log.e(TAG, "Key generation failed completely", e2)
            }
        }
    }

    private fun generateAndSaveKeys(keyFile: File, certFile: File) {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        val keyPair = keyGen.generateKeyPair()
        cachedPrivateKey = keyPair.private

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86400000L) // Yesterday
        val notAfter = Date(now + 86400000L * 365L * 30L) // 30 years
        val name = X500Name("CN=QuickReplacer")
        val serial = BigInteger.valueOf(now)

        val certBuilder = JcaX509v3CertificateBuilder(
            name,
            serial,
            notBefore,
            notAfter,
            name,
            keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val holder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        cachedCertificate = cert

        keyFile.writeBytes(keyPair.private.encoded)
        certFile.writeBytes(cert.encoded)
    }

    override fun getPrivateKey(): PrivateKey {
        return cachedPrivateKey ?: throw IllegalStateException("ADB Private Key not initialized")
    }

    override fun getCertificate(): Certificate {
        return cachedCertificate ?: throw IllegalStateException("ADB Certificate not initialized")
    }

    override fun getDeviceName(): String {
        return "QuickReplacer-${Build.MODEL}"
    }

    fun pairDevice(host: String, port: Int, pairingCode: String): Boolean {
        return try {
            val result = pair(host, port, pairingCode)
            if (result) {
                OptionStore.getInstance(context).setAdbPaired(true)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Pairing error on $host:$port", e)
            false
        }
    }

    fun connectDevice(host: String, port: Int): Boolean {
        return try {
            setHostAddress(host)
            val success = connect(port)
            if (success) {
                OptionStore.getInstance(context).setLastAdbPort(port)
            }
            notifyStateChanged()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Connect error on $host:$port", e)
            notifyStateChanged()
            false
        }
    }

    fun tryAutoConnect(): Boolean {
        if (isConnected) return true
        val lastPort = OptionStore.getInstance(context).getLastAdbPort()
        if (lastPort > 0) {
            try {
                if (connectDevice("127.0.0.1", lastPort)) {
                    return true
                }
            } catch (ignored: Exception) {}
        }
        return try {
            val autoSuccess = autoConnect(context, 2000L)
            notifyStateChanged()
            autoSuccess
        } catch (e: Exception) {
            Log.e(TAG, "AutoConnect failed", e)
            notifyStateChanged()
            false
        }
    }

    fun startMdnsDiscovery(onPortFound: (Int) -> Unit) {
        stopMdnsDiscovery()
        try {
            mdnsDiscoverer = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { address, port ->
                if (port > 0) {
                    OptionStore.getInstance(context).setLastAdbPort(port)
                    onPortFound(port)
                }
            }.apply { start() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mDNS", e)
        }
    }

    fun stopMdnsDiscovery() {
        try {
            mdnsDiscoverer?.stop()
            mdnsDiscoverer = null
        } catch (ignored: Exception) {}
    }

    fun executeCommand(script: String): Pair<Int, String> {
        if (!isConnected) {
            if (!tryAutoConnect()) {
                return Pair(-1, "ADB not connected to localhost")
            }
        }

        var res = internalExecuteCommand(script)
        // If connection was stale or closed before output, attempt reconnect once
        if (res.first == -1 && (res.second.contains("Stream closed", ignoreCase = true) || !isConnected)) {
            Log.w(TAG, "Connection or stream was closed. Attempting auto-connect retry...")
            if (tryAutoConnect()) {
                res = internalExecuteCommand(script)
            }
        }
        return res
    }

    private fun internalExecuteCommand(script: String): Pair<Int, String> {
        var stream: AdbStream? = null
        val output = ByteArrayOutputStream()
        return try {
            val wrapped = "$script\necho -n '$DELIMITER'\necho $?\n"
            stream = openStream("shell:$wrapped")
            val input = stream.openInputStream()
            val buffer = ByteArray(4096)
            var n: Int

            while (true) {
                try {
                    n = input.read(buffer)
                    if (n == -1) break
                    output.write(buffer, 0, n)
                    val str = output.toString("UTF-8")
                    if (str.contains(DELIMITER) && str.endsWith("\n")) {
                        break
                    }
                } catch (ioe: java.io.IOException) {
                    // libadb-android throws IOException("Stream closed.") on EOF when remote adbd exits
                    if (ioe.message?.contains("Stream closed", ignoreCase = true) == true) {
                        break
                    }
                    throw ioe
                }
            }

            val fullText = output.toString("UTF-8")
            val parts = fullText.split(DELIMITER)
            val outputText = parts[0].trim()
            val exitCode = if (parts.size > 1) {
                parts[1].trim().toIntOrNull() ?: 0
            } else {
                if (outputText.contains("OK")) 0 else -1
            }
            Pair(exitCode, outputText)
        } catch (e: Exception) {
            val fullText = output.toString("UTF-8")
            if (fullText.contains(DELIMITER)) {
                val parts = fullText.split(DELIMITER)
                val outputText = parts[0].trim()
                val exitCode = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: 0 else 0
                return Pair(exitCode, outputText)
            }
            Log.e(TAG, "Shell execution failed", e)
            notifyStateChanged()
            Pair(-1, e.message ?: "ADB Stream Error")
        } finally {
            try {
                stream?.close()
            } catch (ignored: Exception) {}
        }
    }
}
