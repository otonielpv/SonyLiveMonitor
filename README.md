# SonyRemoteApp

Monitor en vivo de baja latencia para Sony a6000 (y otras camaras con la
Sony Camera Remote API), pensado como alternativa ligera a Imaging Edge.

Dos componentes:

- **App Android nativa** (`android/`) — el monitor definitivo para el movil.
  Validada en una a6000 real: ~25 fps con ~5 ms de edad de frame.
- **Prototipo de escritorio en Python** (raiz) — util para diagnostico y
  desarrollo desde el PC.

## App Android ("Sony Monitor")

- Liveview a pantalla completa con HUD (fps, edad del frame, drops).
- Cuadriculas configurables: tercios / tercios+diagonales / cruz (persistente).
- Medidor de exposicion en tiempo real calculado del liveview: histograma de
  luminancia, desviacion EV respecto al gris medio y % de recorte
  (la API de Sony no expone el fotometro interno de la camara).
- Controles de camara: ISO, velocidad, apertura, compensacion EV y disparo
  (los valores disponibles se consultan a la camara segun el modo del dial).
- Enfoque tactil tocando la imagen (setTouchAFPosition).
- Reconexion automatica y re-vinculacion a la WiFi activa si cambia de red.

Compilar e instalar (con el SDK de Android y un movil con depuracion USB):

```
cd android
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Uso: camara en "Ctrl. con smartphone", movil conectado a la WiFi
`DIRECT-xxxx:ILCE-6000`, abrir la app. Se reconecta sola.

## Prototipo de escritorio (Python)

## Como funciona

La camara, en modo **Ctrl. con smartphone** (Smart Remote Control), crea un
punto de acceso WiFi y expone una API JSON-RPC. Esta app:

1. Descubre el endpoint por SSDP (o usa `http://192.168.122.1:8080/sony/camera`).
2. Llama a `startRecMode` + `startLiveview` para obtener la URL del stream.
3. Lee el stream de frames JPEG con un socket crudo (`TCP_NODELAY`) en un
   hilo dedicado que **solo conserva el frame mas reciente** — los frames
   atrasados se descartan en vez de encolarse, evitando el lag acumulado.
4. Renderiza con OpenCV mostrando FPS, edad del frame y frames descartados.

## Uso

```
pip install -r requirements.txt

# 1. En la camara: MENU > Aplicacion > Ctrl. con smartphone
# 2. Conecta el PC al WiFi que crea la camara (DIRECT-xxxx:ILCE-6000)
# 3. Ejecuta:
python monitor.py
```

Opciones utiles:

| Opcion | Descripcion |
|---|---|
| `--endpoint URL` | Salta el descubrimiento SSDP (arranque mas rapido) |
| `--size L` | Pide un liveview mas grande si la camara lo soporta |
| `--scale 2` | Factor de escala de la ventana (default 2x) |
| `--no-hud` | Oculta el overlay de FPS/latencia |

Salir: tecla `q` o `ESC`.

## Estructura

- `sony_camera.py` — descubrimiento SSDP y cliente JSON-RPC de la API.
- `liveview_stream.py` — parser del contenedor de liveview con estrategia
  "solo el ultimo frame" para minimizar latencia.
- `monitor.py` — ventana de visualizacion con HUD de rendimiento.
