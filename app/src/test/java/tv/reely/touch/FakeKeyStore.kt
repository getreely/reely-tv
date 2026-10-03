package tv.reely.touch

import android.security.keystore.KeyGenParameterSpec
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * Android's keystore, as far as SecureStore uses it, for tests: the build machine has
 * no hardware to keep keys in, so they're kept in memory. Lets the real view model start.
 */
object FakeKeyStore {
    private val keys = mutableMapOf<String, SecretKey>()

    fun install() {
        if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(FakeProvider())
    }

    class FakeProvider : Provider("AndroidKeyStore", 1.0, "In-memory keystore for tests") {
        init {
            put("KeyStore.AndroidKeyStore", Store::class.java.name)
            put("KeyGenerator.AES", Generator::class.java.name)
        }
    }

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = if (alias in keys) Date(0) else null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key as SecretKey
        }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineContainsAlias(alias: String): Boolean = alias in keys
        override fun engineSize(): Int = keys.size
        override fun engineIsKeyEntry(alias: String): Boolean = alias in keys
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) = Unit
        override fun engineGetEntry(alias: String, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? =
            keys[alias]?.let(KeyStore::SecretKeyEntry)
    }

    class Generator : KeyGeneratorSpi() {
        private var alias = "key"
        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            (params as? KeyGenParameterSpec)?.let { alias = it.keystoreAlias }
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
        override fun engineGenerateKey(): SecretKey {
            val key = KeyGenerator.getInstance("AES", "SunJCE").apply { init(256) }.generateKey()
            keys[alias] = key
            return key
        }
    }
}
