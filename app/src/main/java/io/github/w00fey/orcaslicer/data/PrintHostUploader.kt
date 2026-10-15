package io.github.w00fey.orcaslicer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Uploads G-code to a network print server (OctoPrint, Moonraker or PrusaLink). */
object PrintHostUploader {

    suspend fun upload(host: PrintHost, file: File, remoteName: String, startPrint: Boolean, onProgress: (Float) -> Unit) {
        if (host.type == HostType.FLASHFORGE) {
            FlashforgeClient.upload(host.url, file, remoteName, startPrint, onProgress)
            return
        }
        withContext(Dispatchers.IO) {
            val base = normalizeUrl(host.url)
            when (host.type) {
                HostType.FLASHFORGE -> Unit
                HostType.OCTOPRINT -> multipart(
                    URL("$base/api/files/local"), host.apiKey, file, remoteName,
                    mapOf("print" to startPrint.toString(), "select" to startPrint.toString()), onProgress,
                )
                HostType.MOONRAKER -> multipart(
                    URL("$base/server/files/upload"), host.apiKey, file, remoteName,
                    mapOf("root" to "gcodes", "print" to startPrint.toString()), onProgress,
                )
                HostType.PRUSALINK -> {
                    val name = URLEncoder.encode(remoteName, "UTF-8").replace("+", "%20")
                    val conn = open(URL("$base/api/v1/files/usb/$name"), host.apiKey)
                    conn.requestMethod = "PUT"
                    conn.setRequestProperty("Content-Type", "text/x.gcode")
                    conn.setRequestProperty("Overwrite", "?1")
                    conn.setRequestProperty("Print-After-Upload", if (startPrint) "?1" else "?0")
                    conn.setFixedLengthStreamingMode(file.length())
                    conn.outputStream.use { copyWithProgress(file, it, file.length(), onProgress) }
                    finish(conn)
                }
            }
        }
    }

    /** Checks that the host answers; returns a short description of the server. */
    suspend fun test(host: PrintHost): String {
        if (host.type == HostType.FLASHFORGE) return FlashforgeClient.test(host.url)
        return withContext(Dispatchers.IO) { testHttp(host) }
    }

    private fun testHttp(host: PrintHost): String {
        val base = normalizeUrl(host.url)
        val path = when (host.type) {
            HostType.OCTOPRINT, HostType.PRUSALINK, HostType.FLASHFORGE -> "/api/version"
            HostType.MOONRAKER -> "/server/info"
        }
        val conn = open(URL(base + path), host.apiKey)
        conn.requestMethod = "GET"
        return finish(conn).take(200)
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "Printer address is empty" }
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }

    private fun open(url: URL, apiKey: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 120_000
            if (apiKey.isNotBlank()) setRequestProperty("X-Api-Key", apiKey.trim())
        }

    private suspend fun multipart(
        url: URL, apiKey: String, file: File, remoteName: String,
        fields: Map<String, String>, onProgress: (Float) -> Unit,
    ) {
        val boundary = "----orca" + UUID.randomUUID().toString().replace("-", "")
        val head = buildString {
            for ((k, v) in fields) {
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
            }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"")
            append(remoteName.replace("\"", "_"))
            append("\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        }.toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()

        val conn = open(url, apiKey)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setFixedLengthStreamingMode(head.size + file.length() + tail.size)
        conn.outputStream.use { out ->
            out.write(head)
            copyWithProgress(file, out, file.length(), onProgress)
            out.write(tail)
        }
        finish(conn)
    }

    private suspend fun copyWithProgress(file: File, out: OutputStream, total: Long, onProgress: (Float) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var sent = 0L
        file.inputStream().use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                sent += n
                onProgress(if (total > 0) sent.toFloat() / total else 1f)
            }
        }
    }

    private fun finish(conn: HttpURLConnection): String {
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val hint = when (code) {
                    401, 403 -> " (check the API key)"
                    404 -> " (check the address and server type)"
                    409 -> " (a print may already be running)"
                    else -> ""
                }
                throw IOException("Server replied $code$hint: ${body.take(200)}")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }
}
