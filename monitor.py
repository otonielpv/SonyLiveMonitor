"""Monitor en vivo de baja latencia para Sony a6000.

Uso:
    python monitor.py                  # descubre la camara por SSDP
    python monitor.py --endpoint URL   # endpoint conocido (mas rapido)
    python monitor.py --size L         # pide mayor tamano de liveview
    python monitor.py --scale 2        # escala la ventana (por defecto 2x)

Teclas: q o ESC para salir.
"""

from __future__ import annotations

import argparse
import sys
import time

import cv2
import numpy as np

from liveview_stream import LiveviewStream
from sony_camera import CameraError, SonyCamera

WINDOW = "Sony a6000 - Monitor"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--endpoint", help="URL del endpoint (ej. http://192.168.122.1:8080/sony/camera)")
    p.add_argument("--size", help="Tamano de liveview a pedir (ej. L o M); si falla usa el default")
    p.add_argument("--scale", type=float, default=2.0, help="Factor de escala de la ventana")
    p.add_argument("--no-hud", action="store_true", help="Ocultar overlay de FPS/latencia")
    return p.parse_args()


def draw_hud(img: np.ndarray, fps: float, age_ms: float, dropped: int) -> None:
    text = f"{fps:5.1f} fps | frame age {age_ms:4.0f} ms | dropped {dropped}"
    cv2.putText(img, text, (8, 20), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 0), 3, cv2.LINE_AA)
    cv2.putText(img, text, (8, 20), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 128), 1, cv2.LINE_AA)


def main() -> int:
    args = parse_args()

    print("Conectando con la camara..." if args.endpoint else "Buscando camara por SSDP...", flush=True)
    camera = SonyCamera(args.endpoint)
    print(f"Endpoint: {camera.endpoint}", flush=True)

    try:
        url = camera.start_liveview(prefer_size=args.size)
    except CameraError as e:
        print(f"Error: {e}", file=sys.stderr)
        print(
            "Comprueba que la camara esta en 'Ctrl. con smartphone' y que el PC "
            "esta conectado al WiFi de la camara.",
            file=sys.stderr,
        )
        return 1
    print(f"Liveview: {url}", flush=True)

    stream = LiveviewStream(url)
    stream.start()
    cv2.namedWindow(WINDOW, cv2.WINDOW_AUTOSIZE)

    shown = 0
    fps = 0.0
    fps_t0 = time.monotonic()
    fps_n0 = 0
    last_frame_at = time.monotonic()
    last_img: np.ndarray | None = None
    try:
        while True:
            # No bloquear NUNCA esperando frames: la ventana debe seguir
            # respondiendo aunque el stream se pause (WiFi/puente con hipos).
            frame = stream.poll_frame()
            now = time.monotonic()

            if frame is not None:
                img = cv2.imdecode(np.frombuffer(frame.jpeg, np.uint8), cv2.IMREAD_COLOR)
                if img is not None:  # JPEG corrupto ocasional: se ignora
                    if args.scale != 1.0:
                        img = cv2.resize(
                            img, None, fx=args.scale, fy=args.scale,
                            interpolation=cv2.INTER_LINEAR,
                        )
                    shown += 1
                    last_frame_at = now
                    if now - fps_t0 >= 1.0:
                        fps = (shown - fps_n0) / (now - fps_t0)
                        fps_t0, fps_n0 = now, shown
                    if not args.no_hud:
                        age_ms = (now - frame.received_at) * 1000
                        draw_hud(img, fps, age_ms, stream.frames_dropped)
                    last_img = img
                    cv2.imshow(WINDOW, img)
            else:
                stalled = now - last_frame_at
                if stalled > 30:
                    raise TimeoutError("30s sin frames del liveview")
                if stalled > 1.5 and last_img is not None and not args.no_hud:
                    frozen = last_img.copy()
                    cv2.putText(
                        frozen, f"SIN SENAL {stalled:.0f}s", (8, 45),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2, cv2.LINE_AA,
                    )
                    cv2.imshow(WINDOW, frozen)

            # Bombear eventos SIEMPRE: 1 ms si hay señal fluida, 15 ms en pausa
            key = cv2.waitKey(1 if frame is not None else 15) & 0xFF
            if key in (ord("q"), 27):
                break
            if cv2.getWindowProperty(WINDOW, cv2.WND_PROP_VISIBLE) < 1:
                break
    except (TimeoutError, ConnectionError) as e:
        print(f"Stream interrumpido: {e}", file=sys.stderr)
        return 1
    finally:
        stream.stop()
        camera.stop_liveview()
        cv2.destroyAllWindows()
        print(f"Frames leidos: {stream.frames_read}, mostrados: {shown}, descartados: {stream.frames_dropped}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
