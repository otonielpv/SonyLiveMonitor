"""Lector del stream de liveview de Sony con estrategia anti-lag.

El stream es un GET HTTP de duracion indefinida que emite frames JPEG
envueltos en un contenedor propietario muy simple:

  Common header (8 bytes):
    [0]    start byte (0xFF)
    [1]    payload type (0x01 = imagen liveview, 0x02 = frame info)
    [2:4]  numero de secuencia (big endian)
    [4:8]  timestamp en ms

  Payload header (128 bytes):
    [0:4]  start code 24 35 68 79
    [4:7]  tamano del JPEG (3 bytes, big endian)
    [7]    bytes de padding tras el JPEG
    resto  reservado

  JPEG + padding.

Claves anti-lag:
- Socket crudo con TCP_NODELAY (sin buffering de librerias HTTP).
- Un hilo lee frames tan rapido como llegan y SOLO conserva el mas
  reciente. Si el consumidor va lento, los frames viejos se descartan
  en vez de acumularse en una cola (que es lo que produce el retraso
  creciente tipico de Imaging Edge).
"""

from __future__ import annotations

import socket
import threading
import time
from dataclasses import dataclass
from urllib.parse import urlparse

PAYLOAD_START_CODE = b"\x24\x35\x68\x79"


@dataclass
class Frame:
    jpeg: bytes
    sequence: int
    received_at: float  # time.monotonic() al terminar de leer el frame


class LiveviewStream:
    def __init__(self, url: str):
        self.url = url
        self._sock: socket.socket | None = None
        self._buf = b""  # bytes del stream ya sin framing HTTP
        self._raw = b""  # bytes recibidos pendientes de de-chunkear
        self._chunked = False
        self._chunk_left = 0
        self._latest: Frame | None = None
        self._cond = threading.Condition()
        self._running = False
        self._error: Exception | None = None
        self.frames_read = 0
        self.frames_dropped = 0

    # -- API publica ------------------------------------------------------

    def start(self) -> None:
        self._connect()
        self._running = True
        threading.Thread(target=self._reader, daemon=True).start()

    def stop(self) -> None:
        self._running = False
        if self._sock:
            try:
                self._sock.close()
            except OSError:
                pass

    def poll_frame(self) -> Frame | None:
        """Devuelve el frame mas reciente sin bloquear (None si no hay nuevo)."""
        with self._cond:
            if self._latest is None and self._error is not None:
                raise self._error
            frame, self._latest = self._latest, None
            return frame

    def latest_frame(self, timeout: float = 5.0) -> Frame:
        """Devuelve el frame mas reciente aun no consumido (espera si no hay)."""
        with self._cond:
            if not self._cond.wait_for(
                lambda: self._latest is not None or self._error is not None,
                timeout=timeout,
            ):
                raise TimeoutError("Sin frames del liveview (timeout)")
            if self._latest is None and self._error is not None:
                raise self._error
            frame = self._latest
            self._latest = None
            return frame

    # -- Conexion HTTP minima ----------------------------------------------

    def _connect(self) -> None:
        u = urlparse(self.url)
        port = u.port or 80
        sock = socket.create_connection((u.hostname, port), timeout=10)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        path = u.path + (f"?{u.query}" if u.query else "")
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {u.hostname}:{port}\r\n"
            "Connection: keep-alive\r\n"
            "\r\n"
        )
        sock.sendall(request.encode("ascii"))

        # Consumir cabeceras HTTP de la respuesta
        header = b""
        while b"\r\n\r\n" not in header:
            chunk = sock.recv(4096)
            if not chunk:
                raise ConnectionError("La camara cerro la conexion del liveview")
            header += chunk
        head, _, rest = header.partition(b"\r\n\r\n")
        status = head.split(b"\r\n", 1)[0].decode("ascii", "replace")
        if " 200 " not in f" {status} ":
            raise ConnectionError(f"Liveview HTTP: {status}")
        # La a6000 responde con Transfer-Encoding: chunked; hay que quitar
        # el framing HTTP (lineas "<tam_hex>\r\n" intercaladas) antes de
        # parsear el contenedor de liveview.
        self._chunked = b"transfer-encoding: chunked" in head.lower()
        self._raw = rest
        self._buf = b""
        self._chunk_left = 0
        self._sock = sock

    def _recv_raw(self) -> bytes:
        chunk = self._sock.recv(65536)
        if not chunk:
            raise ConnectionError("Stream de liveview cerrado por la camara")
        return chunk

    def _raw_readline(self) -> bytes:
        while b"\r\n" not in self._raw:
            self._raw += self._recv_raw()
        line, self._raw = self._raw.split(b"\r\n", 1)
        return line

    def _fill(self) -> None:
        """Anade a self._buf mas bytes del stream, ya sin framing HTTP."""
        if not self._chunked:
            if not self._raw:
                self._raw = self._recv_raw()
            self._buf += self._raw
            self._raw = b""
            return
        while self._chunk_left == 0:
            line = self._raw_readline()
            if not line:
                continue  # CRLF que cierra el chunk anterior
            size = int(line.split(b";")[0], 16)
            if size == 0:
                raise ConnectionError("La camara finalizo el stream (chunk 0)")
            self._chunk_left = size
        if not self._raw:
            self._raw = self._recv_raw()
        take = min(self._chunk_left, len(self._raw))
        self._buf += self._raw[:take]
        self._raw = self._raw[take:]
        self._chunk_left -= take

    def _read_exact(self, n: int) -> bytes:
        while len(self._buf) < n:
            self._fill()
        data, self._buf = self._buf[:n], self._buf[n:]
        return data

    # -- Hilo lector ---------------------------------------------------------

    def _reader(self) -> None:
        try:
            while self._running:
                common = self._read_exact(8)
                if common[0] != 0xFF:
                    self._resync()
                    continue
                payload_type = common[1]
                sequence = int.from_bytes(common[2:4], "big")

                payload = self._read_exact(128)
                if payload[:4] != PAYLOAD_START_CODE:
                    self._resync()
                    continue
                data_size = int.from_bytes(payload[4:7], "big")
                padding = payload[7]

                data = self._read_exact(data_size + padding)
                if payload_type != 0x01:  # frame info u otros: no nos interesa
                    continue

                frame = Frame(
                    jpeg=data[:data_size],
                    sequence=sequence,
                    received_at=time.monotonic(),
                )
                self.frames_read += 1
                with self._cond:
                    if self._latest is not None:
                        self.frames_dropped += 1
                    self._latest = frame
                    self._cond.notify()
        except Exception as e:  # noqa: BLE001 - propagar al consumidor
            if self._running:
                with self._cond:
                    self._error = e
                    self._cond.notify()

    def _resync(self) -> None:
        """Busca el proximo start code si perdemos la alineacion del stream."""
        while True:
            i = self._buf.find(PAYLOAD_START_CODE)
            if i >= 8:
                # Retrocede 8 bytes para dejar el common header al inicio
                self._buf = self._buf[i - 8 :]
                return
            if i >= 0:
                # Start code sin common header completo delante: saltarlo
                self._buf = self._buf[i + 4 :]
                continue
            self._buf = self._buf[-11:]  # conserva un posible start code partido
            self._fill()
