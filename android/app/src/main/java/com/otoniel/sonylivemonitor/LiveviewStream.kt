package com.otoniel.sonylivemonitor

import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

class Frame(val jpeg: ByteArray, val sequence: Int, val receivedAtMs: Long)

/**
 * Lector del stream de liveview de Sony con estrategia anti-lag.
 *
 * El stream es un GET HTTP indefinido (la a6000 lo envia con
 * Transfer-Encoding: chunked) que emite frames JPEG envueltos en un
 * contenedor simple:
 *
 *   Common header (8B):  0xFF | tipo (0x01=imagen) | seq (2B BE) | timestamp (4B)
 *   Payload header (128B): startcode 24 35 68 79 | tamano JPEG (3B BE) | padding (1B) | reservado
 *   JPEG + padding
 *
 * Claves anti-lag (las mismas validadas en el prototipo de escritorio):
 * - Socket crudo con TCP_NODELAY.
 * - Un hilo lee tan rapido como llegan los frames y SOLO conserva el mas
 *   reciente: los atrasados se descartan en vez de encolarse.
 * - Reconexion automatica si el stream se corta.
 */
class LiveviewStream(private val url: String) {

    @Volatile private var running = false
    private val lock = Object()
    private var latest: Frame? = null

    @Volatile var framesRead = 0; private set
    @Volatile var framesDropped = 0; private set
    @Volatile var status: String = "connecting"; private set

    private var socket: Socket? = null
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::readerLoop, "liveview-reader").also { it.start() }
    }

    fun stop() {
        running = false
        closeSocket()
        thread?.interrupt()
    }

    /** Espera (con timeout) el frame mas reciente aun no consumido. */
    fun awaitFrame(timeoutMs: Long): Frame? {
        synchronized(lock) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (latest == null) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) return null
                try {
                    lock.wait(left)
                } catch (_: InterruptedException) {
                    return null
                }
            }
            val f = latest
            latest = null
            return f
        }
    }

    // -- hilo lector ---------------------------------------------------------

    private fun readerLoop() {
        while (running) {
            try {
                status = "connecting"
                val input = connect()
                status = "live"
                parseContainer(input)
            } catch (e: Exception) {
                if (!running) return
                status = "reconnecting: ${e.message ?: e.javaClass.simpleName}"
                closeSocket()
                SystemClock.sleep(1000)
            }
        }
    }

    private fun connect(): InputStream {
        val u = URI(url)
        val port = if (u.port > 0) u.port else 80
        val s = Socket()
        s.tcpNoDelay = true
        s.soTimeout = 12_000
        s.connect(InetSocketAddress(u.host, port), 5000)
        socket = s

        val path = if (u.rawQuery != null) "${u.rawPath}?${u.rawQuery}" else u.rawPath
        s.getOutputStream().write(
            ("GET $path HTTP/1.1\r\nHost: ${u.host}:$port\r\nConnection: keep-alive\r\n\r\n")
                .toByteArray(Charsets.US_ASCII)
        )

        val src = BufferedInputStream(s.getInputStream(), 64 * 1024)
        var chunked = false
        var line = readLine(src)
        if (!line.contains(" 200 ")) throw IOException("HTTP: $line")
        while (true) {
            line = readLine(src)
            if (line.isEmpty()) break
            if (line.lowercase().startsWith("transfer-encoding:") && line.lowercase().contains("chunked")) {
                chunked = true
            }
        }
        return if (chunked) ChunkedInputStream(src) else src
    }

    private fun parseContainer(input: InputStream) {
        val common = ByteArray(8)
        val payloadHeader = ByteArray(128)
        while (running) {
            readFully(input, common)
            if (common[0] != 0xFF.toByte()) throw IOException("stream misaligned (common header)")
            val payloadType = common[1].toInt() and 0xFF
            val sequence = ((common[2].toInt() and 0xFF) shl 8) or (common[3].toInt() and 0xFF)

            readFully(input, payloadHeader)
            if (payloadHeader[0] != 0x24.toByte() || payloadHeader[1] != 0x35.toByte() ||
                payloadHeader[2] != 0x68.toByte() || payloadHeader[3] != 0x79.toByte()
            ) throw IOException("stream misaligned (payload header)")

            val dataSize = ((payloadHeader[4].toInt() and 0xFF) shl 16) or
                    ((payloadHeader[5].toInt() and 0xFF) shl 8) or
                    (payloadHeader[6].toInt() and 0xFF)
            val padding = payloadHeader[7].toInt() and 0xFF

            val data = ByteArray(dataSize)
            readFully(input, data)
            skipFully(input, padding.toLong())

            if (payloadType != 0x01) continue  // frame info u otros: ignorar

            framesRead++
            val frame = Frame(data, sequence, SystemClock.elapsedRealtime())
            synchronized(lock) {
                if (latest != null) framesDropped++
                latest = frame
                lock.notifyAll()
            }
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    private fun readLine(src: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = src.read()
            if (b < 0) throw EOFException("stream closed")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readFully(src: InputStream, dst: ByteArray) {
        var off = 0
        while (off < dst.size) {
            val n = src.read(dst, off, dst.size - off)
            if (n < 0) throw EOFException("stream closed")
            off += n
        }
    }

    private fun skipFully(src: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = src.skip(left)
            if (n <= 0) {
                if (src.read() < 0) throw EOFException("stream closed")
                left--
            } else {
                left -= n
            }
        }
    }

    /** Quita el framing HTTP chunked ("<tam_hex>\r\n" intercalado). */
    private inner class ChunkedInputStream(private val src: InputStream) : InputStream() {
        private var remaining = 0
        private var eof = false

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            while (remaining == 0) {
                val line = readLine(src)
                if (line.isEmpty()) continue  // CRLF que cierra el chunk anterior
                remaining = line.substringBefore(';').trim().toInt(16)
                if (remaining == 0) {
                    eof = true
                    return -1
                }
            }
            val n = src.read(b, off, minOf(len, remaining))
            if (n < 0) throw EOFException("stream closed mid-chunk")
            remaining -= n
            return n
        }
    }
}
