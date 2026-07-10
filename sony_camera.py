"""Cliente minimo para la Sony Camera Remote API (a6000 y similares).

La camara expone un endpoint JSON-RPC sobre HTTP cuando esta en modo
"Ctrl. con smartphone" (Smart Remote Control). Este modulo cubre:

- Descubrimiento del endpoint via SSDP (con fallback a la IP tipica).
- Llamadas JSON-RPC (startRecMode, startLiveview, etc.).
"""

from __future__ import annotations

import re
import socket

import requests

DEFAULT_ENDPOINT = "http://192.168.122.1:8080/sony/camera"

SSDP_ADDR = ("239.255.255.250", 1900)
SSDP_ST = "urn:schemas-sony-com:service:ScalarWebAPI:1"
SSDP_MSEARCH = (
    "M-SEARCH * HTTP/1.1\r\n"
    f"HOST: {SSDP_ADDR[0]}:{SSDP_ADDR[1]}\r\n"
    'MAN: "ssdp:discover"\r\n'
    "MX: 2\r\n"
    f"ST: {SSDP_ST}\r\n"
    "\r\n"
)


class CameraError(Exception):
    pass


def discover_endpoint(timeout: float = 3.0) -> str:
    """Busca la camara por SSDP y devuelve la URL del servicio 'camera'.

    Si no responde nada, devuelve DEFAULT_ENDPOINT (la a6000 casi siempre
    usa 192.168.122.1:8080 cuando actua como punto de acceso).
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(timeout)
    try:
        sock.sendto(SSDP_MSEARCH.encode("ascii"), SSDP_ADDR)
        while True:
            try:
                data, _ = sock.recvfrom(4096)
            except socket.timeout:
                return DEFAULT_ENDPOINT
            m = re.search(rb"LOCATION:\s*(\S+)", data, re.IGNORECASE)
            if not m:
                continue
            endpoint = _endpoint_from_description(m.group(1).decode("ascii"))
            if endpoint:
                return endpoint
    finally:
        sock.close()


def _endpoint_from_description(location_url: str) -> str | None:
    """Descarga el XML de descripcion UPnP y extrae la ActionList URL."""
    try:
        xml = requests.get(location_url, timeout=3).text
    except requests.RequestException:
        return None
    m = re.search(
        r"<av:X_ScalarWebAPI_ActionList_URL>(.*?)</av:X_ScalarWebAPI_ActionList_URL>",
        xml,
    )
    if not m:
        return None
    return m.group(1).rstrip("/") + "/camera"


class SonyCamera:
    def __init__(self, endpoint: str | None = None):
        self.endpoint = endpoint or discover_endpoint()
        self._id = 0
        self._session = requests.Session()

    def call(self, method: str, *params, version: str = "1.0"):
        self._id += 1
        payload = {
            "method": method,
            "params": list(params),
            "id": self._id,
            "version": version,
        }
        try:
            resp = self._session.post(self.endpoint, json=payload, timeout=10)
            body = resp.json()
        except requests.RequestException as e:
            raise CameraError(f"No se pudo contactar con la camara: {e}") from e
        if "error" in body:
            code, msg = body["error"]
            raise CameraError(f"{method} fallo: [{code}] {msg}")
        return body.get("result", body.get("results"))

    def start_liveview(self, prefer_size: str | None = None) -> str:
        """Prepara la camara y devuelve la URL del stream de liveview."""
        # La a6000 exige startRecMode antes del liveview. Otros modelos
        # devuelven "No Such Method" (12) o "Already ..." — ambos ignorables.
        try:
            self.call("startRecMode")
        except CameraError:
            pass

        if prefer_size:
            try:
                return self.call("startLiveviewWithSize", prefer_size)[0]
            except CameraError:
                pass  # tamano no soportado: caemos al liveview por defecto
        return self.call("startLiveview")[0]

    def stop_liveview(self) -> None:
        try:
            self.call("stopLiveview")
        except CameraError:
            pass
