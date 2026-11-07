package me.rerere.rikkahub.data.root

import android.content.Context
import android.util.Base64
import android.util.Log
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

private const val TAG = "AdbKeys"
private const val PREFS = "rikkahub.adb"
private const val KEY_PRIVATE = "adb_key_private"
private const val KEY_PUBLIC = "adb_key_public"

/**
 * ADB 用的 RSA 密钥对。
 *
 * 生成一次、存起来，之后一直用它连 adbd —— adbd 认的是「这台设备上记录过的公钥」，
 * 每次都换密钥就会每次都弹授权。
 */
object AdbKeys {

    @Volatile
    private var cached: KeyPair? = null

    fun load(context: Context): KeyPair {
        cached?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val priv = prefs.getString(KEY_PRIVATE, null)
        val pub = prefs.getString(KEY_PUBLIC, null)
        if (priv != null && pub != null) {
            runCatching {
                val factory = KeyFactory.getInstance("RSA")
                val privateKey = factory.generatePrivate(
                    PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))
                )
                val publicKey = factory.generatePublic(
                    X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))
                )
                return KeyPair(publicKey, privateKey).also { cached = it }
            }.onFailure { Log.w(TAG, "existing key unreadable, regenerating", it) }
        }

        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .putString(KEY_PUBLIC, Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            .apply()
        cached = pair
        return pair
    }

    /**
     * ADB 的 `adb_keys` 文件里一行一个公钥，格式：
     *   base64(RSAPublicKey 结构) user@host
     *
     * 中间那段不是 PEM、也不是 X.509，是 adbd 自己定义的二进制结构，
     * 所以这里手工拼：len / n0inv / n[64] / rr[64] / exponent，再跟一句 user@host。
     */
    fun publicKeyLine(context: Context, user: String = "rikkahub@android"): String {
        val pair = load(context)
        return Base64.encodeToString(blob(pair), Base64.NO_WRAP) + " " + user
    }

    private fun blob(pair: KeyPair): ByteArray {
        val pub = pair.public as RSAPublicKey
        val n = pub.modulus
        val e = pub.publicExponent
        val r = BigInteger.ONE.shiftLeft(2048)

        val nLe = ByteArray(64)
        val nBe = n.toByteArray().trimZeros()
        for (i in nBe.indices) nLe[i] = nBe[nBe.size - 1 - i]

        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0 = BigInteger(1, byteArrayOf(nLe[3], nLe[2], nLe[1], nLe[0]))
        val n0inv = n0.modInverse(two32).negate().mod(two32).toLong().toInt()

        val rr = r.multiply(r).mod(n)
        val rrLe = ByteArray(64)
        val rrBe = rr.toByteArray().trimZeros()
        for (i in rrBe.indices) rrLe[i] = rrBe[rrBe.size - 1 - i]

        val out = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(out)
        dos.writeIntLE(140)
        dos.writeIntLE(n0inv)
        dos.write(nLe)
        dos.write(rrLe)
        dos.writeIntLE(e.toInt())
        dos.flush()
        return out.toByteArray()
    }

    /**
     * 把自己的公钥写进系统的 adb_keys（需要 root）。
     *
     * 写进去之后 adbd 会直接信任这台机器，不需要走无线调试的配对流程。
     * 这是有 root 时验证 ADB 协议实现的最快路径。
     *
     * 注意：某些 ROM 的 adbd 只在启动时读一次 adb_keys，写完可能要重启 adbd
     * 或者重连一次才生效。
     */
    suspend fun installToSystem(context: Context): Result<String> {
        if (!RootShell.isAvailable()) {
            return Result.failure(IllegalStateException("需要 root 才能写 adb_keys"))
        }
        val line = publicKeyLine(context)
        // 用 sh 追加，避免引号地狱：把公钥先写到临时文件再 cat 过去
        val tmp = "/data/local/tmp/rikkahub_adbkey.pub"
        runCatching {
            RootShell.exec("rm -f $tmp && echo '$line' > $tmp", 8_000)
        }.onFailure { return Result.failure(it) }

        val result = RootShell.exec(
            "mkdir -p /data/misc/adb 2>/dev/null; " +
                "cat $tmp >> /data/misc/adb/adb_keys; " +
                "chmod 640 /data/misc/adb/adb_keys; " +
                "restorecon /data/misc/adb/adb_keys 2>/dev/null; " +
                "rm -f $tmp; " +
                "echo installed",
            15_000,
        )
        return if (result.ok) {
            Result.success("已写入 adb_keys。如果还是连不上，重启一次 adbd 或者重新插拔 USB。")
        } else {
            Result.failure(IllegalStateException("写入失败：${result.stderr.ifBlank { result.stdout }}"))
        }
    }

    private fun ByteArray.trimZeros(): ByteArray {
        var start = 0
        while (start < size - 1 && this[start] == 0.toByte()) start++
        return copyOfRange(start, size)
    }

    private fun java.io.DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }
}
