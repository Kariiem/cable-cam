package com.cablecam.stream

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamServerTest {
    private class Events : StreamServer.Listener {
        @Volatile var clients = 0
        val keyRequests = AtomicInteger()
        override fun onClientsChanged(clients: List<StreamServer.ClientInfo>) { this.clients = clients.size }
        override fun onNeedKeyframe() { keyRequests.incrementAndGet() }
    }

    private val events = Events()
    private var server = StreamServer(0, events, status = { "status line" })

    @After fun tearDown() = server.stop()

    private fun connect(request: String): Socket {
        val s = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        s.soTimeout = 5000
        s.getOutputStream().write(request.toByteArray())
        return s
    }

    private fun get(path: String) = connect("GET $path HTTP/1.1\r\nHost: 10.1.2.3:8765\r\nUser-Agent: test\r\n\r\n")

    private fun readHeader(input: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) sb.append(input.read().also { check(it >= 0) }.toChar())
        return sb.toString()
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) got += input.read(out, got, n - got).also { check(it > 0) }
        return out
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) { check(System.nanoTime() < end) { "timeout: $what" }; Thread.sleep(5) }
    }

    private fun frame(i: Int, key: Boolean, size: Int = 300) = Frame(ByteArray(size) { i.toByte() }, i * 33_333L, key)

    private fun pids(ts: ByteArray) = (ts.indices step 188).map {
        assertEquals(0x47, ts[it].toInt())
        (ts[it + 1].toInt() and 0x1F shl 8) or (ts[it + 2].toInt() and 0xFF)
    }

    @Test fun statusPage() {
        server.start()
        get("/").use { s ->
            val text = s.getInputStream().readBytes().decodeToString()
            assertTrue(text, text.startsWith("HTTP/1.0 200 OK\r\n"))
            assertTrue(text, "http://10.1.2.3:8765/cam.ts" in text)
            assertTrue(text, "status line" in text)
        }
    }

    @Test fun unknownPathAndBadRequest() {
        server.start()
        get("/nope").use { assertTrue(it.getInputStream().readBytes().decodeToString().startsWith("HTTP/1.0 404")) }
        connect("hello\r\n\r\n").use { assertTrue(it.getInputStream().readBytes().decodeToString().startsWith("HTTP/1.0 400")) }
    }

    @Test fun forbiddenPeer() {
        server = StreamServer(0, events, allowed = { false })
        server.start()
        get("/cam.ts").use { assertTrue(it.getInputStream().readBytes().decodeToString().startsWith("HTTP/1.0 403")) }
        assertEquals(0, server.clientCount)
    }

    @Test fun streamStartsWithTablesThenWaitsForKeyframe() {
        server.start()
        get("/cam.ts?x=1").use { s ->
            val input = s.getInputStream()
            val header = readHeader(input)
            assertTrue(header, header.startsWith("HTTP/1.0 200 OK\r\n"))
            assertTrue(header, "Content-Type: video/mp2t" in header)
            assertEquals(listOf(TsMuxer.PAT_PID, TsMuxer.PMT_PID), pids(readFully(input, 2 * 188)))
            waitFor("client registered") { events.clients == 1 }
            assertTrue(events.keyRequests.get() >= 1)

            server.broadcast(frame(1, key = false))            // dropped: no keyframe seen yet
            server.broadcast(frame(2, key = true))
            server.broadcast(frame(3, key = false))
            // keyframe: PAT, PMT, then 300+20 PES bytes = 2 packets; delta frame: 2 packets
            val ts = readFully(input, 6 * 188)
            assertEquals(
                listOf(TsMuxer.PAT_PID, TsMuxer.PMT_PID) + List(4) { TsMuxer.VIDEO_PID }, pids(ts)
            )
            assertEquals("first video payload is frame 2", 2, ts[3 * 188 - 1].toInt())
            assertEquals(3, ts[6 * 188 - 1].toInt())
        }
        waitFor("client gone") { server.broadcast(frame(9, true)); events.clients == 0 }
    }

    @Test fun slowClientDropsToNextKeyframe() {
        server.start()
        get("/cam.ts").use { s ->
            val input = s.getInputStream()
            readHeader(input); readFully(input, 2 * 188)
            waitFor("client registered") { events.clients == 1 }
            val before = events.keyRequests.get()
            // The client does not read: 4 MB fill the socket buffers and then the queue overflows.
            server.broadcast(frame(0, key = true, size = 100_000))
            repeat(40) { server.broadcast(frame(it + 1, key = false, size = 100_000)) }
            assertTrue("frames were dropped", server.framesDropped.get() > 0)
            assertTrue("a keyframe was requested", events.keyRequests.get() > before)
            val dropped = server.framesDropped.get()
            server.broadcast(frame(50, key = false, size = 100_000))   // still waiting for a keyframe
            assertEquals(dropped, server.framesDropped.get())

            // Now read everything: the stream must stay packet aligned and end with the new keyframe.
            server.broadcast(frame(77, key = true, size = 500))
            val got = ByteArrayOutputStream()
            val done = CountDownLatch(1)
            Thread {
                runCatching {
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        synchronized(got) { got.write(buf, 0, n) }
                    }
                }
                done.countDown()
            }.start()
            waitFor("keyframe 77 arrives") {
                val b = synchronized(got) { got.toByteArray() }
                b.size % 188 == 0 && b.isNotEmpty() && b.last() == 77.toByte()
            }
            pids(synchronized(got) { got.toByteArray() })          // every packet starts with the sync byte
            s.close()
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun twoClientsAndDropClients() {
        server.start()
        val a = get("/cam.ts"); val b = get("/cam.ts")
        waitFor("two clients") { events.clients == 2 }
        server.dropClients()
        waitFor("clients dropped") { server.broadcast(frame(1, true)); events.clients == 0 }
        a.close(); b.close()
    }
}
