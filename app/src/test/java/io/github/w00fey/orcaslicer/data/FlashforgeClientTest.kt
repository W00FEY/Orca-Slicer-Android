package io.github.w00fey.orcaslicer.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Runs the upload against a fake printer on port 8899 that behaves like the
 * Adventurer 3 firmware: "CMD Mxx Received." lines followed by "ok", and raw
 * file bytes between ~M28 and ~M29.
 */
class FlashforgeClientTest {
    private lateinit var server: ServerSocket
    private val commands = mutableListOf<String>()
    private var received: ByteArray? = null
    @Volatile private var refuseUpload = false

    @Before
    fun start() {
        server = ServerSocket(8899)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    @After
    fun stop() = server.close()

    private fun serve(socket: Socket) = socket.use { s ->
        val input = s.getInputStream()
        val out = s.getOutputStream()
        fun reply(vararg lines: String) {
            out.write((lines.joinToString("\r\n") + "\r\nok\r\n").toByteArray())
            out.flush()
        }
        while (true) {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return
                if (b == '\n'.code) break
                if (b != '\r'.code) line.append(b.toChar())
            }
            val cmd = line.toString()
            synchronized(commands) { commands += cmd }
            when {
                cmd.startsWith("~M601") -> reply("CMD M601 Received.", "Control Success.")
                cmd.startsWith("~M115") -> reply("CMD M115 Received.", "Machine Type: Flashforge Adventurer III", "Machine Name: Adventurer3", "Firmware: v1.3.7")
                cmd.startsWith("~M28") -> {
                    if (refuseUpload) { reply("CMD M28 Received.", "open failed, File: 0:/user/x"); continue }
                    val size = cmd.split(" ")[1].toInt()
                    reply("CMD M28 Received.", "Writing to file: ${cmd.substringAfterLast(' ')}")
                    val data = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (data.size() < size) {
                        val n = input.read(buf, 0, minOf(buf.size, size - data.size()))
                        if (n < 0) return
                        data.write(buf, 0, n)
                    }
                    received = data.toByteArray()
                }
                cmd.startsWith("~M29") -> reply("CMD M29 Received.", "Done saving file.")
                else -> reply("CMD ${cmd.removePrefix("~").substringBefore(' ')} Received.")
            }
        }
    }

    @Test
    fun uploadsFileAndStartsPrint(): Unit = runBlocking {
        val file = File.createTempFile("benchy", ".gcode")
        val content = Random(42).nextBytes(300_000)
        file.writeBytes(content)
        var lastProgress = 0f

        FlashforgeClient.upload("127.0.0.1", file, "3D Benchy (1).gcode", startPrint = true) { lastProgress = it }

        assertArrayEquals(content, received)
        assertEquals(1f, lastProgress)
        val cmds = synchronized(commands) { commands.toList() }
        assertEquals(
            listOf("~M601 S1", "~M28 300000 0:/user/3D_Benchy__1_.gcode", "~M29", "~M23 0:/user/3D_Benchy__1_.gcode", "~M602"),
            cmds,
        )
        file.delete()
    }

    @Test
    fun uploadWithoutStartingPrint(): Unit = runBlocking {
        val file = File.createTempFile("cube", ".gcode").apply { writeText("G28\nG1 X10\n") }
        FlashforgeClient.upload("127.0.0.1", file, "cube.gcode", startPrint = false) {}
        val cmds = synchronized(commands) { commands.toList() }
        assertTrue(cmds.none { it.startsWith("~M23") })
        assertEquals("G28\nG1 X10\n", String(received!!))
        file.delete()
    }

    @Test
    fun reportsRefusedUpload(): Unit = runBlocking {
        refuseUpload = true
        val file = File.createTempFile("cube", ".gcode").apply { writeText("G28\n") }
        val error = runCatching { FlashforgeClient.upload("127.0.0.1", file, "cube.gcode", false) {} }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message?.contains("refused") == true)
        file.delete()
    }

    @Test
    fun testConnectionReportsMachine(): Unit = runBlocking {
        val info = FlashforgeClient.test("http://127.0.0.1/")
        assertTrue(info, info.contains("Adventurer III"))
    }

    @Test
    fun sanitizesNames() {
        assertEquals("my_part.gcode", FlashforgeClient.sanitize("my part"))
        assertEquals("a_b.gcode", FlashforgeClient.sanitize("dir/a+b.gcode"))
    }
}
