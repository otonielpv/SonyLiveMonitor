import Foundation
import Darwin

struct LiveviewError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

private func errnoText() -> String { String(cString: strerror(errno)) }

final class Frame {
    let jpeg: Data
    let sequence: Int
    let receivedAt: TimeInterval  // reloj monotono (systemUptime)

    init(jpeg: Data, sequence: Int, receivedAt: TimeInterval) {
        self.jpeg = jpeg
        self.sequence = sequence
        self.receivedAt = receivedAt
    }
}

/**
 Lector del stream de liveview de Sony con estrategia anti-lag.

 El stream es un GET HTTP indefinido (la a6000 lo envia con
 Transfer-Encoding: chunked) que emite frames JPEG envueltos en un
 contenedor simple:

   Common header (8B):  0xFF | tipo (0x01=imagen) | seq (2B BE) | timestamp (4B)
   Payload header (128B): startcode 24 35 68 79 | tamano JPEG (3B BE) | padding (1B) | reservado
   JPEG + padding

 Claves anti-lag (las mismas validadas en el prototipo de escritorio):
 - Socket crudo con TCP_NODELAY.
 - Un hilo lee tan rapido como llegan los frames y SOLO conserva el mas
   reciente: los atrasados se descartan en vez de encolarse.
 - Reconexion automatica si el stream se corta.
 */
final class LiveviewStream {

    private let url: String
    private let cond = NSCondition()
    private var latest: Frame?
    private var runningStorage = false
    private var framesReadStorage = 0
    private var framesDroppedStorage = 0
    private var statusStorage = "connecting"

    private let fdLock = NSLock()
    private var fd: Int32 = -1

    private var thread: Thread?

    init(url: String) { self.url = url }

    var framesRead: Int { cond.lock(); defer { cond.unlock() }; return framesReadStorage }
    var framesDropped: Int { cond.lock(); defer { cond.unlock() }; return framesDroppedStorage }
    var status: String { cond.lock(); defer { cond.unlock() }; return statusStorage }
    private var isRunning: Bool { cond.lock(); defer { cond.unlock() }; return runningStorage }
    private func setStatus(_ s: String) { cond.lock(); statusStorage = s; cond.unlock() }

    func start() {
        cond.lock(); runningStorage = true; cond.unlock()
        let t = Thread { [weak self] in self?.readerLoop() }
        t.name = "liveview-reader"
        thread = t
        t.start()
    }

    func stop() {
        cond.lock(); runningStorage = false; cond.broadcast(); cond.unlock()
        closeSocket()
    }

    /// Espera (con timeout) el frame mas reciente aun no consumido.
    func awaitFrame(timeout: TimeInterval) -> Frame? {
        let deadline = Date().addingTimeInterval(timeout)
        cond.lock()
        defer { cond.unlock() }
        while latest == nil {
            if !cond.wait(until: deadline) { return nil }
        }
        let frame = latest
        latest = nil
        return frame
    }

    // -- hilo lector ---------------------------------------------------------

    private func readerLoop() {
        while isRunning {
            do {
                setStatus("connecting")
                let input = try connect()
                setStatus("live")
                try parseContainer(input)
            } catch {
                if !isRunning { return }
                let detail = (error as? LiveviewError)?.message ?? error.localizedDescription
                setStatus("reconnecting: \(detail)")
                closeSocket()
                Thread.sleep(forTimeInterval: 1)
            }
        }
    }

    private func connect() throws -> FrameInput {
        guard let comps = URLComponents(string: url), let host = comps.host else {
            throw LiveviewError("bad liveview URL: \(url)")
        }
        let port = comps.port ?? 80
        var path = comps.percentEncodedPath.isEmpty ? "/" : comps.percentEncodedPath
        if let query = comps.percentEncodedQuery { path += "?" + query }

        let fd = try openSocket(host: host, port: port)
        fdLock.lock(); self.fd = fd; fdLock.unlock()

        let request = [UInt8]("GET \(path) HTTP/1.1\r\nHost: \(host):\(port)\r\nConnection: keep-alive\r\n\r\n".utf8)
        var sent = 0
        while sent < request.count {
            let n = request.withUnsafeBytes { Darwin.send(fd, $0.baseAddress! + sent, request.count - sent, 0) }
            guard n > 0 else { throw LiveviewError("send: \(errnoText())") }
            sent += n
        }

        let buffered = BufferedInput(fd: fd)
        var chunked = false
        var line = try buffered.readLine()
        guard line.contains(" 200 ") else { throw LiveviewError("HTTP: \(line)") }
        while true {
            line = try buffered.readLine()
            if line.isEmpty { break }
            let lower = line.lowercased()
            if lower.hasPrefix("transfer-encoding:") && lower.contains("chunked") { chunked = true }
        }
        return chunked ? ChunkedInput(buffered) : buffered
    }

    private func openSocket(host: String, port: Int) throws -> Int32 {
        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_STREAM
        var info: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, String(port), &hints, &info) == 0, let first = info else {
            throw LiveviewError("cannot resolve \(host)")
        }
        defer { freeaddrinfo(info) }

        let fd = socket(first.pointee.ai_family, first.pointee.ai_socktype, first.pointee.ai_protocol)
        guard fd >= 0 else { throw LiveviewError("socket: \(errnoText())") }

        var one: Int32 = 1
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
        var timeout = timeval(tv_sec: 12, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

        // connect no bloqueante con timeout de 5 s (el connect bloqueante
        // de BSD puede tardar ~75 s en rendirse)
        let flags = fcntl(fd, F_GETFL, 0)
        _ = fcntl(fd, F_SETFL, flags | O_NONBLOCK)
        if Darwin.connect(fd, first.pointee.ai_addr, first.pointee.ai_addrlen) != 0 {
            guard errno == EINPROGRESS else {
                let msg = errnoText()
                close(fd)
                throw LiveviewError("connect: \(msg)")
            }
            var pfd = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
            guard poll(&pfd, 1, 5000) == 1 else {
                close(fd)
                throw LiveviewError("connect timeout")
            }
            var err: Int32 = 0
            var len = socklen_t(MemoryLayout<Int32>.size)
            getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len)
            guard err == 0 else {
                close(fd)
                throw LiveviewError("connect: \(String(cString: strerror(err)))")
            }
        }
        _ = fcntl(fd, F_SETFL, flags)
        return fd
    }

    private func parseContainer(_ input: FrameInput) throws {
        while isRunning {
            let common = [UInt8](try input.readFully(count: 8))
            guard common[0] == 0xFF else { throw LiveviewError("stream misaligned (common header)") }
            let payloadType = Int(common[1])
            let sequence = (Int(common[2]) << 8) | Int(common[3])

            let ph = [UInt8](try input.readFully(count: 128))
            guard ph[0] == 0x24, ph[1] == 0x35, ph[2] == 0x68, ph[3] == 0x79 else {
                throw LiveviewError("stream misaligned (payload header)")
            }
            let dataSize = (Int(ph[4]) << 16) | (Int(ph[5]) << 8) | Int(ph[6])
            let padding = Int(ph[7])

            let data = try input.readFully(count: dataSize)
            try input.skip(padding)

            guard payloadType == 0x01 else { continue }  // frame info u otros: ignorar

            let frame = Frame(jpeg: data, sequence: sequence,
                              receivedAt: ProcessInfo.processInfo.systemUptime)
            cond.lock()
            framesReadStorage += 1
            if latest != nil { framesDroppedStorage += 1 }
            latest = frame
            cond.broadcast()
            cond.unlock()
        }
    }

    private func closeSocket() {
        fdLock.lock()
        if fd >= 0 {
            // shutdown desbloquea un read() en curso; close() solo no lo hace
            shutdown(fd, SHUT_RDWR)
            close(fd)
            fd = -1
        }
        fdLock.unlock()
    }
}

// -- fuentes de bytes ----------------------------------------------------------

private protocol FrameInput {
    func readFully(count: Int) throws -> Data
    func skip(_ count: Int) throws
}

/// Lectura buffereada directa del socket.
private final class BufferedInput: FrameInput {
    private let fd: Int32
    private var buf = [UInt8](repeating: 0, count: 64 * 1024)
    private var pos = 0
    private var limit = 0

    init(fd: Int32) { self.fd = fd }

    private func fill() throws {
        while true {
            let n = buf.withUnsafeMutableBytes { Darwin.read(fd, $0.baseAddress, $0.count) }
            if n > 0 { pos = 0; limit = n; return }
            if n == 0 { throw LiveviewError("stream closed") }
            if errno == EINTR { continue }
            if errno == EAGAIN || errno == EWOULDBLOCK { throw LiveviewError("read timeout") }
            throw LiveviewError("read: \(errnoText())")
        }
    }

    private func readByte() throws -> UInt8 {
        if pos == limit { try fill() }
        defer { pos += 1 }
        return buf[pos]
    }

    func readLine() throws -> String {
        var bytes = [UInt8]()
        while true {
            let b = try readByte()
            if b == 0x0A { break }
            if b != 0x0D { bytes.append(b) }
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    func readFully(count: Int) throws -> Data {
        var out = Data(capacity: count)
        var left = count
        while left > 0 {
            if pos == limit { try fill() }
            let take = min(left, limit - pos)
            buf.withUnsafeBufferPointer { out.append($0.baseAddress! + pos, count: take) }
            pos += take
            left -= take
        }
        return out
    }

    func skip(_ count: Int) throws {
        var left = count
        while left > 0 {
            if pos == limit { try fill() }
            let take = min(left, limit - pos)
            pos += take
            left -= take
        }
    }
}

/// Quita el framing HTTP chunked ("<tam_hex>\r\n" intercalado).
private final class ChunkedInput: FrameInput {
    private let src: BufferedInput
    private var remaining = 0
    private var eof = false

    init(_ src: BufferedInput) { self.src = src }

    private func nextChunk() throws {
        while remaining == 0 {
            if eof { throw LiveviewError("stream closed") }
            let line = try src.readLine()
            if line.isEmpty { continue }  // CRLF que cierra el chunk anterior
            let hex = line.split(separator: ";").first.map(String.init) ?? line
            guard let size = Int(hex.trimmingCharacters(in: .whitespaces), radix: 16) else {
                throw LiveviewError("bad chunk size: \(line)")
            }
            if size == 0 { eof = true; throw LiveviewError("stream closed") }
            remaining = size
        }
    }

    func readFully(count: Int) throws -> Data {
        var out = Data(capacity: count)
        var left = count
        while left > 0 {
            try nextChunk()
            let take = min(left, remaining)
            out.append(try src.readFully(count: take))
            remaining -= take
            left -= take
        }
        return out
    }

    func skip(_ count: Int) throws {
        var left = count
        while left > 0 {
            try nextChunk()
            let take = min(left, remaining)
            try src.skip(take)
            remaining -= take
            left -= take
        }
    }
}
