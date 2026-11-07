package me.rerere.rikkahub.data.root

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

private const val TAG = "AdbClient"

/**
 * 最小的 ADB 协议客户端（Kotlin 实现）。
 *
 * 为什么自己写：Google 不提供 ARM 版 adb 二进制，Termux 那套带授权问题；
 * DSHA 是拿 Python 的 adb_shell_wifi 做的，思路同样是「自己封包」。
 *
 * 只实现跑命令需要的部分：
 *
 *   CNXN  建立连接（声明 host 身份与 maxdata）
 *   AUTH  认证：adbd 发 token → 用 RSA 私钥签名回传
 *         首次连接会把公钥发过去，手机上弹「允许 USB 调试」，用户点允许即可
 *   OPEN  开一条 `shell:<命令>` 的流
 *   WRTE  流数据（双向）
 *   OKAY  流控确认
 *   CLSE  关闭
 *
 * 包格式（全部小端）：
 *   command(4) arg0(4) arg1(4) dataLength(4) dataCrc32(4) magic(4)
 *   magic = command xor 0xFFFFFFFF
 *
 * **不含配对**：Android 11 的「无线调试」需要 TLS-PSK + SPAKE2 先配对，
 * 那是另一套协议。这里的用法是：
 *   1. 先在电脑上 `adb tcpip 5555`（或用 USB 连一次让设备信任这台机）
 *   2. App 用 RSA 密钥连 adbd，手机上点一次「允许」即可
 * 配对流程留作下一步。
 */
class AdbClient(
    private val host: String,
    private val port: Int,
    private val keyPair: KeyPair = defaultKeyPair(),
) {

    companion object {
        private const val A_VERSION = 0x01000001
        private const val A_MAXDATA = 4096
        private const val CMD_CNXN = 0x4e584e43
        private const val CMD_AUTH = 0x48545541
        private const val CMD_OPEN = 0x4e45504f
        private const val CMD_OKAY = 0x59414b4f
        private const val CMD_CLSE = 0x45534c43
        private const val CMD_WRTE = 0x45545257

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3

        /** 软件生成一对 RSA 密钥（第一次用时会慢几百毫秒）。 */
        fun defaultKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            return generator.generateKeyPair()
        }
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String, val ok: Boolean)

    /** 建连 + 认证。失败抛异常，调用方决定怎么提示。 */
    fun connect(timeoutMs: Int = 6_000) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        socket = s
        input = DataInputStream(s.getInputStream())
        output = DataOutputStream(s.getOutputStream())

        send(CMD_CNXN, A_VERSION, A_MAXDATA, "host::\u0000".toByteArray())

        var authorized = false
        repeat(6) {
            val packet = read()
            when (packet.command) {
                CMD_CNXN -> {
                    authorized = true
                    return@repeat
                }
                CMD_AUTH -> {
                    if (packet.arg0 == AUTH_TOKEN) {
                        // 先用私钥签 token
                        val signature = sign(packet.data)
                        send(CMD_AUTH, AUTH_SIGNATURE, 0, signature)
                    } else if (packet.arg0 == AUTH_RSAPUBLICKEY) {
                        // adbd 要公钥：发过去后手机上会弹授权框
                        send(CMD_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob())
                    }
                }
                else -> Unit
            }
            if (authorized) return@repeat
        }
        if (!authorized) {
            throw IllegalStateException("ADB 认证未完成（手机上是不是没点「允许」？）")
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    /**
     * 在设备上跑一条 shell 命令，拿回 stdout / stderr / 退出码。
     *
     * 实现方式是把命令包成 `shell:<cmd>` 开一条流，收集 WRTE 数据，
     * 并在命令末尾加 `echo __EXIT__$?` 以便拿到真实退出码 ——
     * ADB 的 shell 协议本身不返回 exit code。
     */
    fun shell(command: String, timeoutMs: Long = 60_000): ShellResult {
        val inp = input ?: error("not connected")
        val out = output ?: error("not connected")
        val localId = 1

        val wrapped = "$command; echo __EXIT__\$?"
        send(CMD_OPEN, localId, 0, "shell:$wrapped\u0000".toByteArray())

        val buffer = StringBuilder()
        var remoteId = -1
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val packet = runCatching { read() }.getOrElse { break }
            when (packet.command) {
                CMD_OKAY -> {
                    if (remoteId < 0) remoteId = packet.arg0
                }
                CMD_WRTE -> {
                    if (remoteId < 0) remoteId = packet.arg0
                    buffer.append(String(packet.data, Charsets.UTF_8))
                    // 收到数据要回 OKAY 让对端继续发
                    send(CMD_OKAY, localId, packet.arg0, ByteArray(0))
                }
                CMD_CLSE -> {
                    break
                }
                else -> Unit
            }
        }

        val raw = buffer.toString()
        val exitIdx = raw.lastIndexOf("__EXIT__")
        val exitCode = if (exitIdx >= 0) {
            raw.substring(exitIdx + 8).trim().toIntOrNull() ?: -1
        } else {
            -1
        }
        val body = if (exitIdx >= 0) raw.substring(0, exitIdx) else raw
        // ADB shell 把 stdout/stderr 合并到一条流里，这里不区分
        return ShellResult(exitCode, body.trimEnd('\n', '\r'), "", exitCode == 0)
    }

    // ---- 协议底层 ---------------------------------------------------------

    private data class Packet(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray,
    )

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val out = output ?: error("not connected")
        val magic = command.inv()
        val crc = crc32(data)
        out.writeIntLE(command)
        out.writeIntLE(arg0)
        out.writeIntLE(arg1)
        out.writeIntLE(data.size)
        out.writeIntLE(crc)
        out.writeIntLE(magic)
        if (data.isNotEmpty()) out.write(data)
        out.flush()
    }

    private fun read(): Packet {
        val inp = input ?: error("not connected")
        val command = inp.readIntLE()
        val arg0 = inp.readIntLE()
        val arg1 = inp.readIntLE()
        val length = inp.readIntLE()
        inp.readIntLE() // crc，不校验
        val magic = inp.readIntLE()
        if (magic != command.inv()) error("bad magic")
        val data = ByteArray(length.coerceIn(0, 1024 * 1024))
        if (data.isNotEmpty()) inp.readFully(data)
        return Packet(command, arg0, arg1, data)
    }

    private fun DataInputStream.readIntLE(): Int {
        val b0 = read(); val b1 = read(); val b2 = read(); val b3 = read()
        if ((b0 or b1 or b2 or b3) < 0) error("eof")
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
    }

    private fun DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun crc32(data: ByteArray): Int {
        var crc = -1
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        return crc.inv()
    }

    /** 用 RSA 私钥签 adbd 给的 token（PKCS#1 v1.5 + SHA-1，ADB 的约定）。 */
    private fun sign(token: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(keyPair.private)
        signature.update(token)
        return signature.sign()
    }

    /**
     * ADB 的 RSAPublicKey 二进制结构（不是 PEM）。
     *
     *   struct RSAPublicKey {
     *       uint32 len;        // 下面这些字段的总长，固定 140
     *       uint32 n0inv;      // -1 / n[0] mod 2^32
     *       uint8  n[64];      // modulus，小端
     *       uint8  rr[64];     // R^2 mod n，R = 2^2048，小端
     *       uint32 exponent;
     *   };
     * 后面再跟 " user@host\0"。
     *
     * n0inv 和 rr 都得真算，随便填会被 adbd 直接拒。
     */
    private fun publicKeyBlob(): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        val n = pub.modulus
        val e = pub.publicExponent

        val r = BigInteger.ONE.shiftLeft(2048)

        // modulus 转 64 字节小端
        val nLe = ByteArray(64)
        val nBe = n.toByteArray().trimLeadingZerosBe()
        for (i in nBe.indices) nLe[i] = nBe[nBe.size - 1 - i]

        // n0inv = -1 / n mod 2^32
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0 = BigInteger(1, byteArrayOf(nLe[3], nLe[2], nLe[1], nLe[0])) // 前 4 字节小端 → 大端给 BigInteger
        val n0inv = n0.modInverse(two32).negate().mod(two32).toLong().toInt()

        // rr = R^2 mod n
        val rr = r.multiply(r).mod(n)
        val rrLe = ByteArray(64)
        val rrBe = rr.toByteArray().trimLeadingZerosBe()
        for (i in rrBe.indices) rrLe[i] = rrBe[rrBe.size - 1 - i]

        val out = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(out)
        dos.writeIntLE(140)            // len
        dos.writeIntLE(n0inv)
        dos.write(nLe)
        dos.write(rrLe)
        dos.writeIntLE(e.toInt())
        dos.write(" rikkahub@android\u0000".toByteArray())
        dos.flush()
        return out.toByteArray()
    }

    private fun ByteArray.trimLeadingZerosBe(): ByteArray {
        var start = 0
        while (start < size - 1 && this[start] == 0.toByte()) start++
        return copyOfRange(start, size)
    }
}
