package io.github.w00fey.orcaslicer.data

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

data class FlashforgePrinter(val name: String, val serial: String, val address: String)

/**
 * Network control of Flashforge printers that use the "legacy" TCP G-code
 * protocol on port 8899 (Adventurer 3 / 3 Pro / 4, Finder, Dreamer, Guider).
 *
 * Upload sequence, matching OrcaSlicer's desktop Flashforge host:
 *   ~M601 S1                       take control
 *   ~M28 <bytes> 0:/user/<name>    open a file on the printer
 *   <raw file bytes>
 *   ~M29                           close and save the file
 *   ~M23 0:/user/<name>            optionally start printing it
 * Every command is answered with text lines ending in "ok".
 */
object FlashforgeClient {
    private const val CONTROL_PORT = 8899
    private const val DISCOVERY_PORT = 48899
    private const val DISCOVERY_LISTEN_PORT = 18007
    private val DISCOVERY_MESSAGE = byteArrayOf(
        0x77, 0x77, 0x77, 0x2e, 0x75, 0x73, 0x72, 0x22, 0x65, 0x36, 0xc0.toByte(),
        0, 0, 0, 0, 0, 0, 0, 0, 0,
    )

    /** Sends the file and optionally starts the print. */
    suspend fun upload(address: String, file: File, remoteName: String, startPrint: Boolean, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val name = sanitize(remoteName)
            Connection(hostOf(address)).use { c ->
                c.command("~M601 S1")
                val open = c.command("~M28 ${file.length()} 0:/user/$name")
                if (open.contains("fail", ignoreCase = true) || open.contains("error", ignoreCase = true)) {
                    throw IOException("The printer refused the upload: ${open.trim()}")
                }
                val buffer = ByteArray(16 * 1024)
                var sent = 0L
                file.inputStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        c.out.write(buffer, 0, n)
                        sent += n
                        onProgress(sent.toFloat() / file.length().coerceAtLeast(1))
                    }
                }
                c.out.flush()
                // Saving to the printer's storage can take a while for large files.
                val saved = c.command("~M29", timeoutMs = 60_000)
                if (saved.contains("not available", ignoreCase = true) || saved.contains("fail", ignoreCase = true)) {
                    throw IOException("The printer could not save the file: ${saved.trim()}")
                }
                if (startPrint) c.command("~M23 0:/user/$name", timeoutMs = 20_000)
                runCatching { c.command("~M602") } // release control
            }
        }

    /** Returns the printer's self-description (model, firmware). */
    suspend fun test(address: String): String = withContext(Dispatchers.IO) {
        Connection(hostOf(address)).use { c ->
            c.command("~M601 S1")
            val info = c.command("~M115")
            runCatching { c.command("~M602") }
            info.lines().map { it.trim() }
                .filter { it.startsWith("Machine Type", true) || it.startsWith("Machine Name", true) || it.startsWith("Firmware", true) }
                .joinToString(", ")
                .ifEmpty { "Flashforge printer" }
        }
    }

    /** Finds Flashforge printers on the local network via UDP broadcast. */
    suspend fun discover(context: Context, timeoutMs: Int = 3000): List<FlashforgePrinter> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Some phones drop broadcast packets unless a multicast lock is held.
        val lock = wifi.createMulticastLock("orca-flashforge-discovery").apply { setReferenceCounted(false); acquire() }
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.bind(InetSocketAddress(DISCOVERY_LISTEN_PORT))
                socket.soTimeout = 250
                for (target in broadcastAddresses(wifi)) {
                    runCatching {
                        socket.send(DatagramPacket(DISCOVERY_MESSAGE, DISCOVERY_MESSAGE.size, InetAddress.getByName(target), DISCOVERY_PORT))
                    }
                }
                val found = LinkedHashMap<String, FlashforgePrinter>()
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(512)
                while (System.currentTimeMillis() < deadline) {
                    coroutineContext.ensureActive()
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    parseDiscovery(packet.data.copyOf(packet.length), packet.address.hostAddress ?: continue)
                        ?.let { found[it.address] = it }
                }
                found.values.sortedBy { it.name }
            }
        } finally {
            lock.release()
        }
    }

    private fun parseDiscovery(data: ByteArray, address: String): FlashforgePrinter? {
        if (data.size < 0xC4) return null
        fun text(offset: Int, len: Int) =
            String(data, offset, len, Charsets.US_ASCII).substringBefore('\u0000').trim()
        val name = text(0, 32)
        val serial = text(0x92, 32)
        return if (name.isEmpty() && serial.isEmpty()) null else FlashforgePrinter(name.ifEmpty { "Flashforge" }, serial, address)
    }

    @Suppress("DEPRECATION") // DhcpInfo is the simplest way to get the subnet broadcast address.
    private fun broadcastAddresses(wifi: WifiManager): List<String> {
        val out = linkedSetOf("255.255.255.255")
        wifi.dhcpInfo?.let { d ->
            if (d.ipAddress != 0 && d.netmask != 0) {
                val bc = (d.ipAddress and d.netmask) or d.netmask.inv()
                out += listOf(bc and 0xff, (bc shr 8) and 0xff, (bc shr 16) and 0xff, (bc shr 24) and 0xff).joinToString(".")
            }
        }
        return out.toList()
    }

    /** Accepts "192.168.1.20", "192.168.1.20:8899" or "http://192.168.1.20". */
    private fun hostOf(address: String): String {
        val a = address.trim().substringAfter("://").substringBefore('/').substringBefore(':')
        require(a.isNotEmpty()) { "Printer address is empty" }
        return a
    }

    /** The printer only accepts plain ASCII names; keep the .gcode extension. */
    fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "print.gcode" }
        return if (base.contains('.')) base else "$base.gcode"
    }

    private class Connection(host: String) : AutoCloseable {
        private val socket = Socket().apply {
            connect(InetSocketAddress(host, CONTROL_PORT), 5000)
            tcpNoDelay = true
        }
        val out: OutputStream = socket.getOutputStream().buffered(64 * 1024)
        private val input: InputStream = socket.getInputStream()

        /** Sends a command and reads the reply up to the terminating "ok" line. */
        fun command(cmd: String, timeoutMs: Int = 10_000): String {
            out.write("$cmd\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            socket.soTimeout = timeoutMs
            val reply = StringBuilder()
            val line = StringBuilder()
            while (true) {
                val b = try {
                    input.read()
                } catch (_: SocketTimeoutException) {
                    throw IOException("The printer did not answer ${cmd.substringBefore(' ')} in time")
                }
                if (b < 0) throw IOException("The printer closed the connection")
                if (b == '\n'.code) {
                    val l = line.toString().trim()
                    reply.appendLine(l)
                    line.clear()
                    if (l.equals("ok", ignoreCase = true) || l.endsWith(" ok", ignoreCase = true)) return reply.toString()
                } else if (b != '\r'.code) {
                    line.append(b.toChar())
                }
            }
        }

        override fun close() = socket.close()
    }
}
