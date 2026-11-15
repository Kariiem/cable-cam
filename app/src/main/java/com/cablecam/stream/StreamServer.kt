package com.cablecam.stream

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** One encoded access unit (Annex-B; keyframes start with SPS/PPS). Immutable once broadcast. */
class Frame(val data: ByteArray, val ptsUs: Long, val key: Boolean)

/**
 * Tiny HTTP server: `GET /cam.ts` answers with an endless MPEG-TS stream, `GET /` with a status
 * page. No Android classes, so it is unit tested on the JVM with real sockets.
 *
 * Every stream client has its own muxer, writer thread and bounded queue. A client that cannot
 * keep up loses whole frames up to the next keyframe; latency never builds up.
 */
class StreamServer(
    private val port: Int,
    private val listener: Listener,
    private val allowed: (InetAddress) -> Boolean = Access::allowed,
    private val status: () -> String = { "" },
) {
    interface Listener {
        /** A stream client connected or left. Called on a server thread. */
        fun onClientsChanged(clients: List<ClientInfo>)
        /** A client needs a keyframe soon (it just connected, or it dropped frames). */
        fun onNeedKeyframe()
    }

    /** [local] is the phone-side address of the connection: it tells which link the client uses. */
    data class ClientInfo(val remote: InetAddress, val local: InetAddress)

    val bytesSent = AtomicLong()
    val framesDropped = AtomicLong()

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private val connections = CopyOnWriteArrayList<Socket>()

    val localPort: Int get() = serverSocket?.localPort ?: -1
    val clientCount: Int get() = clients.size

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (serverSocket != null) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        Thread({ acceptLoop(ss) }, "cam-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        val ss = serverSocket ?: return
        serverSocket = null
        runCatching { ss.close() }
        connections.forEach { runCatching { it.close() } }
    }

    /** Disconnects the stream clients (they reconnect); used when the video format changes. */
    fun dropClients() = clients.forEach { runCatching { it.socket.close() } }

    fun broadcast(frame: Frame) {
        for (c in clients) if (c.offer(frame)) listener.onNeedKeyframe()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (true) {
            val s = try { ss.accept() } catch (e: IOException) { return }
            if (connections.size >= MAX_CONNECTIONS) { runCatching { s.close() }; continue }
            connections += s
            Thread({
                try { serve(s) } catch (e: IOException) { /* client went away */ } finally {
                    runCatching { s.close() }
                    connections -= s
                }
            }, "cam-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(s: Socket) {
        s.tcpNoDelay = true
        s.soTimeout = REQUEST_TIMEOUT_MS
        val out = s.getOutputStream()
        val request = readRequest(s.getInputStream())
        val parts = request?.lineSequence()?.firstOrNull()?.split(' ')
        val path = parts?.getOrNull(1)?.substringBefore('?')
        when {
            !allowed(s.inetAddress) ->
                respond(out, "403 Forbidden", "Cable Cam only serves private network addresses.\n")
            parts == null || parts.size < 3 || parts[0] != "GET" || path == null ->
                respond(out, "400 Bad Request", "Bad request.\n")
            path == STREAM_PATH -> stream(s, out)
            path == "/" -> {
                val host = request.lineSequence().firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() } ?: "<phone-ip>:$port"
                respond(
                    out, "200 OK",
                    "Cable Cam is running.\n\nOBS Media Source input:\n  http://$host$STREAM_PATH\n\n" +
                        "Clients connected: ${clients.size}\n${status()}\n"
                )
            }
            else -> respond(out, "404 Not Found", "Not found. The stream is at $STREAM_PATH\n")
        }
    }

    private fun stream(s: Socket, out: OutputStream) {
        s.sendBufferSize = SEND_BUFFER       // small, so a slow link backs up into our queue, not the kernel's
        runCatching { s.trafficClass = TRAFFIC_CLASS }
        s.soTimeout = 0
        out.write(
            ("HTTP/1.0 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache, no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        val client = Client(s)
        out.write(client.muxer.psi())
        out.flush()
        clients += client
        notifyClients()
        listener.onNeedKeyframe()
        try {
            while (true) {
                val f = client.take(KEEPALIVE_MS)
                val bytes = when {
                    f == null -> TsMuxer.NULL_PACKET       // nothing to send: this notices a closed connection
                    f.key -> client.muxer.psi() + client.muxer.video(f.data, f.ptsUs, true)
                    else -> client.muxer.video(f.data, f.ptsUs, false)
                }
                out.write(bytes)
                out.flush()
                bytesSent.addAndGet(bytes.size.toLong())
            }
        } finally {
            clients -= client
            notifyClients()
        }
    }

    /** Under a lock, so that concurrent joins and leaves are reported in order (the last report is current). */
    private fun notifyClients() = synchronized(clients) {
        listener.onClientsChanged(clients.map { ClientInfo(it.socket.inetAddress, it.socket.localAddress) })
    }

    private fun respond(out: OutputStream, code: String, text: String) {
        val body = text.toByteArray()
        out.write(
            ("HTTP/1.0 $code\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    /** Reads up to the blank line that ends the request headers; null if it is not a sane request. */
    private fun readRequest(input: InputStream): String? {
        val buf = ByteArray(MAX_REQUEST)
        var n = 0
        while (n < buf.size) {
            val b = input.read()
            if (b < 0) return null
            buf[n++] = b.toByte()
            if (n >= 4 && buf[n - 1] == '\n'.code.toByte() && buf[n - 2] == '\r'.code.toByte() &&
                buf[n - 3] == '\n'.code.toByte() && buf[n - 4] == '\r'.code.toByte()
            ) return String(buf, 0, n, Charsets.ISO_8859_1)
        }
        return null
    }

    private inner class Client(val socket: Socket) {
        val muxer = TsMuxer()
        private val queue = ArrayDeque<Frame>()
        private var queuedBytes = 0
        private var waitKey = true           // a stream has to start at a keyframe

        /** Queues the frame, or drops it. Returns true if this client now needs a keyframe. */
        fun offer(f: Frame): Boolean = synchronized(queue) {
            if (waitKey) {
                if (!f.key) return false
                waitKey = false
            }
            if (queue.size >= MAX_QUEUED_FRAMES || queuedBytes + f.data.size > MAX_QUEUED_BYTES) {
                framesDropped.addAndGet(queue.size.toLong())
                queue.clear(); queuedBytes = 0
                if (!f.key) {
                    framesDropped.incrementAndGet()
                    waitKey = true
                    return true
                }
            }
            queue.addLast(f); queuedBytes += f.data.size
            (queue as Object).notifyAll()
            false
        }

        fun take(timeoutMs: Long): Frame? = synchronized(queue) {
            if (queue.isEmpty()) (queue as Object).wait(timeoutMs)
            val f = queue.removeFirstOrNull() ?: return null
            queuedBytes -= f.data.size
            f
        }
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val STREAM_PATH = "/cam.ts"
        /**
         * About one second of video. Wi-Fi stalls of a few hundred ms are normal and are absorbed here without
         * losing a frame (the backlog is sent at link speed afterwards); only a longer stall drops frames,
         * so latency cannot grow without limit.
         */
        const val MAX_QUEUED_FRAMES = 30
        const val MAX_QUEUED_BYTES = 3 shl 20
        /** Kept small, so frames wait here (where they can be dropped if ever needed) and not in the kernel. */
        private const val SEND_BUFFER = 32 * 1024
        /** DSCP AF41: Wi-Fi's WMM puts this in the video queue, which gets air time before best-effort traffic. */
        private const val TRAFFIC_CLASS = 0x88
        private const val MAX_CONNECTIONS = 8
        private const val MAX_REQUEST = 8192
        private const val REQUEST_TIMEOUT_MS = 5000
        private const val KEEPALIVE_MS = 2000L
    }
}
