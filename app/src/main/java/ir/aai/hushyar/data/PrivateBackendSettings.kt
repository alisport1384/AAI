package ir.aai.hushyar.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ir.aai.hushyar.domain.PersonalBackendConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Opt-in private settings. Keystore-sealed token is NOT put into SQLite or exported ZIP backups. */
class PrivateBackendSettings(private val context: Context) {
    private val prefs get() = context.getSharedPreferences("hushyar.personal.backend.v1", Context.MODE_PRIVATE)

    fun location(): Pair<String, String> =
        prefs.getString("url", "").orEmpty() to prefs.getString("pin", "").orEmpty()

    fun enabled(): Boolean = prefs.getBoolean("enabled", false)

    fun read(): PersonalBackendConfig? {
        if (!enabled()) return null
        val (url, pin) = location()
        val sealed = prefs.getString("sealed-token", null) ?: throw IllegalStateException("کلید بک‌اند یافت نشد؛ اتصال را دوباره ثبت کن")
        return try {
            PersonalBackendConfig.of(url, pin, decrypt(sealed))
        } catch (_: Exception) {
            throw IllegalStateException("کلید امن بک‌اند خوانده نشد؛ اتصال را دوباره ثبت کن")
        }
    }

    fun save(config: PersonalBackendConfig) {
        // Encrypt first; a failed Keystore operation must never leave an enabled plaintext secret.
        val sealed = encrypt(config.token)
        check(prefs.edit().putString("url", config.baseUrl).putString("pin", config.pinSha256)
            .putString("sealed-token", sealed).putBoolean("enabled", true).commit()) {
            "تنظیمات بک‌اند روی دستگاه ذخیره نشد"
        }
    }

    fun clear() {
        check(prefs.edit().clear().commit()) { "حذف تنظیمات بک‌اند ناموفق بود" }
        // Even if Keystore removal fails, the encrypted token is already gone from the phone.
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { store ->
                if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
            }
        }
    }

    private fun getKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "کلید رمزگذاری در این نصب پیدا نشد" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    private fun encrypt(secret: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey(create = true))
        val iv = cipher.iv
        check(iv.size == 12) { "طول IV نامنتظره است" }
        val bytes = iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decrypt(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        check(bytes.size > 28) { "دادهٔ کلید ناقص است" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(create = false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    companion object { private const val ALIAS = "hushyar_personal_backend_token_v1" }
}
