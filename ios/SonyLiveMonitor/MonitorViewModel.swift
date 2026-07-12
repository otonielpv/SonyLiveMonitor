import UIKit
import Combine

enum GridMode: Int, CaseIterable {
    case off, thirds, thirdsDiag, cross

    var label: String {
        switch self {
        case .off: return "off"
        case .thirds: return "3x3"
        case .thirdsDiag: return "3x3+X"
        case .cross: return "cross"
        }
    }
}

struct Exposure {
    let hist: [Int]           // 64 bins de luminancia
    let evOffset: Float       // desviacion respecto al gris medio (18%)
    let clipShadows: Float    // % de pixeles recortados en sombras
    let clipHighlights: Float // % de pixeles recortados en altas luces
}

/// Ajuste editable en vivo desde el panel de control.
struct CameraSetting: Identifiable {
    let id: String
    let getMethod: String
    let setMethod: String
    var numeric = false  // el setter espera un numero, no un string
    let chipLabel: (String) -> String

    /// getAvailableX devuelve valores editables; getX devuelve siempre el
    /// valor actual y es mas fiable durante la inicializacion en la a6000.
    var currentMethod: String { getMethod.replacingOccurrences(of: "getAvailable", with: "get") }
}

/// Tira horizontal de valores de un ajuste abierta en el panel.
struct ValueStrip {
    let owner: String
    var values: [String]
    var current: String?
    let apply: (String) -> Void  // llamar desde el hilo principal
}

final class MonitorViewModel: ObservableObject {

    static let settings: [CameraSetting] = [
        CameraSetting(id: "ISO", getMethod: "getAvailableIsoSpeedRate", setMethod: "setIsoSpeedRate",
                      chipLabel: { "ISO \($0)" }),
        CameraSetting(id: "Shutter", getMethod: "getAvailableShutterSpeed", setMethod: "setShutterSpeed",
                      chipLabel: { $0 }),
        CameraSetting(id: "Aperture", getMethod: "getAvailableFNumber", setMethod: "setFNumber",
                      chipLabel: { "f/\($0)" }),
        CameraSetting(id: "Focus", getMethod: "getAvailableFocusMode", setMethod: "setFocusMode",
                      chipLabel: { $0 }),
        CameraSetting(id: "Flash", getMethod: "getAvailableFlashMode", setMethod: "setFlashMode",
                      chipLabel: { "Flash \($0)" }),
        CameraSetting(id: "Timer", getMethod: "getAvailableSelfTimer", setMethod: "setSelfTimer",
                      numeric: true, chipLabel: { "T:\($0)s" }),
    ]

    // -- estado publicado (solo se toca en el hilo principal) --------------------

    @Published var image: UIImage?
    @Published var hudText: String?
    @Published var alertText: String?
    @Published var statusMessage: String? = "Waiting for the camera WiFi..."
    @Published var zoomText: String?
    @Published var exposure: Exposure?
    @Published var focusMarker: CGPoint?
    @Published var toast: String?
    @Published var strip: ValueStrip?
    @Published var chipLabels: [String: String] = [:]
    @Published var rotation: Int
    @Published var grid: GridMode
    @Published var meterOn: Bool
    @Published var hudOn: Bool
    @Published var shootMode = "still"
    @Published var movieRecording = false
    @Published var showConnectHelp = false
    @Published var showGallery = false
    // Salud del enlace con la camara. iOS no expone el RSSI del WiFi, asi que
    // se estima del transporte real: huecos entre frames (jitter) + drops. Es
    // el sintoma directo de mala red, que es lo que importa para diagnosticar.
    @Published var linkText: String?          // ej. "link: fair (1.5 gaps/s)"
    @Published var linkQuality = 0            // 0=desconocido 1=good 2=fair 3=weak
    @Published var diagnosticsReport: String?  // no-nil mientras se muestra la hoja

    private let apiQueue = DispatchQueue(label: "camera-api")
    private let defaults = UserDefaults.standard

    // Leidos tambien desde el hilo del monitor (carrera benigna, como en Android)
    private var active = false
    private var capturing = false
    private var worker: Thread?
    private var zoomTextGeneration = 0
    private var focusGeneration = 0
    private var toastGeneration = 0

    // Como maximo puede haber una actualizacion de video esperando en main.
    // Sin esta coalescencia, una bajada puntual de rendimiento acumula un
    // UIImage por frame y puede terminar agotando memoria y congelando la UI.
    private let framePublishLock = NSLock()
    private var framePublishScheduled = false
    private var pendingFramePublish: (UIImage?, String?, String)?
    private let exposureRefreshLock = NSLock()
    private var exposureRefreshPending = false

    init() {
        rotation = defaults.integer(forKey: "rot")
        grid = GridMode(rawValue: defaults.integer(forKey: "grid")) ?? .off
        meterOn = defaults.bool(forKey: "meter")
        hudOn = defaults.object(forKey: "hud") == nil ? true : defaults.bool(forKey: "hud")
        for s in Self.settings { chipLabels[s.id] = s.id }
        chipLabels["EV"] = "EV"
        chipLabels["WB"] = "WB"
        chipLabels["MODE"] = "Mode: Photo"
    }

    // -- ciclo de vida ------------------------------------------------------------

    /// Detiene el liveview y abre la galeria de la tarjeta a pantalla completa.
    /// El monitor se reinicia al cerrar la galeria (onDismiss en ContentView).
    func openGallery() {
        stop()
        showGallery = true
    }

    func start() {
        guard !active, !showGallery else { return }
        active = true
        UIApplication.shared.isIdleTimerDisabled = true
        let t = Thread { [weak self] in self?.monitorLoop() }
        t.name = "monitor"
        worker = t
        t.start()
    }

    func stop() {
        active = false
        UIApplication.shared.isIdleTimerDisabled = false
        worker = nil
    }

    // -- toggles persistentes -------------------------------------------------------

    func cycleRotation() {
        rotation = (rotation + 90) % 360
        defaults.set(rotation, forKey: "rot")
    }

    func cycleGrid() {
        grid = GridMode(rawValue: (grid.rawValue + 1) % GridMode.allCases.count) ?? .off
        defaults.set(grid.rawValue, forKey: "grid")
    }

    func toggleMeter() {
        meterOn.toggle()
        defaults.set(meterOn, forKey: "meter")
        if !meterOn { exposure = nil }
    }

    func toggleHud() {
        hudOn.toggle()
        defaults.set(hudOn, forKey: "hud")
    }

    // -- bucle principal (hilo propio, nunca bloquea la UI) ---------------------------

    private func monitorLoop() {
        while active {
            do {
                publishMessage("Connecting to the camera...")
                let url = try SonyCamera.startLiveview()
                refreshChips()
                streamAndRender(url: url)
            } catch {
                if !active { return }
                let message: String
                if error is URLError {
                    // Aun no estamos en la WiFi de la camara: instrucciones, no error crudo
                    message = "Waiting for the camera WiFi...\n\n"
                        + "On the camera: 'Ctrl w/ Smartphone'.\n"
                        + "On the iPhone: Settings > WiFi and join the\n"
                        + "DIRECT-xxxx network shown on the camera."
                } else {
                    message = "Error: \(error.localizedDescription)\nRetrying..."
                }
                publishMessage(message)
                Thread.sleep(forTimeInterval: 2)
            }
        }
    }

    private func streamAndRender(url: String) {
        let stream = LiveviewStream(url: url)
        stream.start()
        defer { stream.stop() }

        var fps: Float = 0
        var fpsWindowStart = ProcessInfo.processInfo.systemUptime
        var fpsWindowCount = 0
        var lastFrameAt = fpsWindowStart
        var lastMeterAt: TimeInterval = 0
        var lastCameraStateAt: TimeInterval = 0
        // Ventana para la salud del enlace: contamos "huecos" (intervalos entre
        // frames mayores de 120 ms, ~3x el periodo a 25 fps) y drops por segundo.
        var linkWindowStart = fpsWindowStart
        var gapCount = 0
        var dropsAtWindowStart = 0

        while active {
            let frame = stream.awaitFrame(timeout: 0.25)
            let now = ProcessInfo.processInfo.systemUptime

            guard let frame else {
                let stalled = now - lastFrameAt
                if capturing {
                    // El liveview se pausa mientras la camara captura y procesa
                    // la foto: es normal, no es perdida de senal.
                    lastFrameAt = now
                    publishFrame(nil, alert: "CAPTURING...", fps: fps, ageMs: -1, dropped: stream.framesDropped)
                    continue
                }
                if stalled > 20 { return }  // sin frames 20 s: reiniciar liveview desde cero
                if stalled > 1.2 {
                    publishFrame(nil, alert: "NO SIGNAL \(Int(stalled))s (\(stream.status))",
                                 fps: fps, ageMs: -1, dropped: stream.framesDropped)
                }
                continue
            }

            guard let decoded = UIImage(data: frame.jpeg) else { continue }  // JPEG corrupto ocasional

            fpsWindowCount += 1
            // Un intervalo largo entre frames = hueco de transporte (jitter/red)
            if now - lastFrameAt > 0.12 { gapCount += 1 }
            lastFrameAt = now
            if now - fpsWindowStart >= 1 {
                fps = Float(fpsWindowCount) / Float(now - fpsWindowStart)
                fpsWindowStart = now
                fpsWindowCount = 0
            }
            if now - linkWindowStart >= 1 {
                let elapsed = now - linkWindowStart
                let gapsPerSec = Double(gapCount) / elapsed
                let dropsPerSec = Double(stream.framesDropped - dropsAtWindowStart) / elapsed
                updateLinkHealth(gapsPerSec: gapsPerSec, dropsPerSec: dropsPerSec)
                linkWindowStart = now
                gapCount = 0
                dropsAtWindowStart = stream.framesDropped
            }

            let ageMs = Int((ProcessInfo.processInfo.systemUptime - frame.receivedAt) * 1000)

            if now - lastCameraStateAt >= 1 {
                lastCameraStateAt = now
                refreshExposureChips()
            }

            // El analisis de exposicion es caro: muestrear ~7 veces/s basta
            if meterOn, now - lastMeterAt >= 0.15, let cg = decoded.cgImage {
                lastMeterAt = now
                let exp = ExposureMeter.compute(cg)
                DispatchQueue.main.async { self.exposure = exp }
            }

            publishFrame(decoded, alert: nil, fps: fps, ageMs: ageMs, dropped: stream.framesDropped)
        }
    }

    private func publishFrame(_ image: UIImage?, alert: String?, fps: Float, ageMs: Int, dropped: Int) {
        let hud = ageMs >= 0
            ? String(format: "%.1f fps | age %d ms | drops %d", fps, ageMs, dropped)
            : String(format: "%.1f fps | drops %d", fps, dropped)

        framePublishLock.lock()
        pendingFramePublish = (image, alert, hud)
        let shouldSchedule = !framePublishScheduled
        if shouldSchedule { framePublishScheduled = true }
        framePublishLock.unlock()
        guard shouldSchedule else { return }

        DispatchQueue.main.async {
            self.framePublishLock.lock()
            let update = self.pendingFramePublish
            self.pendingFramePublish = nil
            self.framePublishScheduled = false
            self.framePublishLock.unlock()

            guard let (image, alert, hud) = update else { return }
            self.statusMessage = nil
            if let image { self.image = image }
            self.hudText = hud
            self.alertText = alert
        }
    }

    /// Traduce jitter (huecos/s) y drops/s a una salud de enlace legible.
    /// Umbrales conservadores: a 25 fps un enlace sano tiene ~0 huecos.
    private func updateLinkHealth(gapsPerSec: Double, dropsPerSec: Double) {
        let quality: Int      // 1=good 2=fair 3=weak
        if gapsPerSec >= 3 || dropsPerSec >= 8 {
            quality = 3
        } else if gapsPerSec >= 1 || dropsPerSec >= 3 {
            quality = 2
        } else {
            quality = 1
        }
        let label = quality == 1 ? "good" : (quality == 2 ? "fair" : "weak")
        let text = String(format: "link: %@ (%.1f gaps/s)", label, gapsPerSec)
        DispatchQueue.main.async {
            self.linkQuality = quality
            self.linkText = text
        }
    }

    /// Genera el informe de diagnostico y lo publica para mostrar en una hoja.
    func runDiagnostics() {
        showToast("Reading camera info...")
        apiQueue.async {
            let report = SonyCamera.diagnostics()
            DispatchQueue.main.async { self.diagnosticsReport = report }
        }
    }

    private func publishMessage(_ message: String) {
        DispatchQueue.main.async {
            self.statusMessage = message
            self.hudText = nil
            self.alertText = nil
            self.linkText = nil       // sin stream no hay salud de enlace valida
            self.linkQuality = 0
        }
    }

    // -- acciones de camara ------------------------------------------------------------

    private static func stringify(_ value: Any) -> String {
        if let n = value as? NSNumber { return n.stringValue }
        return "\(value)"
    }

    private func applySetting(_ method: String, value: Any, onOK: @escaping () -> Void) {
        apiQueue.async {
            do {
                try SonyCamera.call(method, params: [value])
                DispatchQueue.main.async(execute: onOK)
            } catch {
                self.showToast("Error: \(error.localizedDescription)")
            }
        }
    }

    /// Abre/cierra la tira de valores de un ajuste. Cada valor se aplica al
    /// instante y la tira permanece abierta para seguir ajustando.
    func toggleStrip(for setting: CameraSetting) {
        if strip?.owner == setting.id { strip = nil; return }
        strip = ValueStrip(owner: setting.id, values: [], current: nil, apply: { _ in })
        apiQueue.async {
            do {
                let r = try SonyCamera.call(setting.getMethod)
                guard r.count >= 2, let cand = r[1] as? [Any], !cand.isEmpty else {
                    throw CameraError("not available in this mode")
                }
                let current = Self.stringify(r[0])
                let values = cand.map(Self.stringify)
                DispatchQueue.main.async {
                    guard self.strip?.owner == setting.id else { return }
                    self.strip = ValueStrip(owner: setting.id, values: values, current: current) { value in
                        self.applySetting(setting.setMethod,
                                          value: setting.numeric ? (Int(value) ?? 0) : value) {
                            self.chipLabels[setting.id] = setting.chipLabel(value)
                            if self.strip?.owner == setting.id { self.strip?.current = value }
                        }
                    }
                }
            } catch {
                self.showToast("\(setting.id): \(error.localizedDescription)")
                DispatchQueue.main.async {
                    if self.strip?.owner == setting.id { self.strip = nil }
                }
            }
        }
    }

    /// La compensacion EV va por indices de paso (1/3 o 1/2 EV por paso).
    func toggleEvStrip() {
        if strip?.owner == "EV" { strip = nil; return }
        strip = ValueStrip(owner: "EV", values: [], current: nil, apply: { _ in })
        apiQueue.async {
            do {
                let r = try SonyCamera.call("getAvailableExposureCompensation")
                guard r.count >= 4,
                      let cur = (r[0] as? NSNumber)?.intValue,
                      let maxStep = (r[1] as? NSNumber)?.intValue,
                      let minStep = (r[2] as? NSNumber)?.intValue,
                      let stepIndex = (r[3] as? NSNumber)?.intValue,
                      minStep <= maxStep else {
                    throw CameraError("unexpected response")
                }
                let stepEv = stepIndex == 2 ? 0.5 : 1.0 / 3.0
                let steps = Array(minStep...maxStep)
                let labels = steps.map { String(format: "%+.1f", Double($0) * stepEv) }
                let current = String(format: "%+.1f", Double(cur) * stepEv)
                DispatchQueue.main.async {
                    guard self.strip?.owner == "EV" else { return }
                    self.strip = ValueStrip(owner: "EV", values: labels, current: current) { label in
                        guard let index = labels.firstIndex(of: label) else { return }
                        self.applySetting("setExposureCompensation", value: steps[index]) {
                            self.chipLabels["EV"] = "\(label) EV"
                            if self.strip?.owner == "EV" { self.strip?.current = label }
                        }
                    }
                }
            } catch {
                self.showToast("EV: \(error.localizedDescription)")
                DispatchQueue.main.async { if self.strip?.owner == "EV" { self.strip = nil } }
            }
        }
    }

    /// El balance de blancos usa objetos {whiteBalanceMode: ...} en la API.
    func toggleWbStrip() {
        if strip?.owner == "WB" { strip = nil; return }
        strip = ValueStrip(owner: "WB", values: [], current: nil, apply: { _ in })
        apiQueue.async {
            do {
                let r = try SonyCamera.call("getAvailableWhiteBalance")
                guard r.count >= 2,
                      let current = (r[0] as? [String: Any])?["whiteBalanceMode"] as? String,
                      let cand = r[1] as? [[String: Any]] else {
                    throw CameraError("unexpected response")
                }
                let modes = cand.compactMap { $0["whiteBalanceMode"] as? String }
                DispatchQueue.main.async {
                    guard self.strip?.owner == "WB" else { return }
                    self.strip = ValueStrip(owner: "WB", values: modes, current: current) { mode in
                        self.apiQueue.async {
                            do {
                                // Para "Color Temperature" hace falta un kelvin: 5500 por defecto
                                let temp = mode == "Color Temperature"
                                try SonyCamera.call("setWhiteBalance",
                                                    params: [mode, temp, temp ? 5500 : 0])
                                DispatchQueue.main.async {
                                    self.chipLabels["WB"] = "WB \(Self.trimWbSuffix(mode))"
                                    if self.strip?.owner == "WB" { self.strip?.current = mode }
                                }
                            } catch {
                                self.showToast("WB: \(error.localizedDescription)")
                            }
                        }
                    }
                }
            } catch {
                self.showToast("WB: \(error.localizedDescription)")
                DispatchQueue.main.async { if self.strip?.owner == "WB" { self.strip = nil } }
            }
        }
    }

    /// Cambio entre foto y video (el disparador pasa a iniciar/parar REC).
    func toggleModeStrip() {
        if strip?.owner == "MODE" { strip = nil; return }
        strip = ValueStrip(owner: "MODE", values: [], current: nil, apply: { _ in })
        apiQueue.async {
            do {
                let r = try SonyCamera.call("getAvailableShootMode")
                guard r.count >= 2, let current = r[0] as? String, let cand = r[1] as? [String] else {
                    throw CameraError("unexpected response")
                }
                DispatchQueue.main.async {
                    guard self.strip?.owner == "MODE" else { return }
                    self.strip = ValueStrip(owner: "MODE", values: cand, current: current) { mode in
                        self.applySetting("setShootMode", value: mode) {
                            self.shootMode = mode
                            self.movieRecording = false
                            self.chipLabels["MODE"] = mode == "movie" ? "Mode: Video" : "Mode: Photo"
                            if self.strip?.owner == "MODE" { self.strip?.current = mode }
                        }
                    }
                }
            } catch {
                self.showToast("Mode: \(error.localizedDescription)")
                DispatchQueue.main.async { if self.strip?.owner == "MODE" { self.strip = nil } }
            }
        }
    }

    func closeStrip() {
        strip = nil
    }

    /// Zoom motorizado: mantener pulsado para acercar/alejar.
    func zoom(direction: String, movement: String) {
        apiQueue.async {
            do {
                try SonyCamera.call("actZoom", params: [direction, movement])
            } catch {
                if movement == "start" { self.showToast("Zoom: requires a power zoom lens") }
            }
        }
        pollZoomPosition()
        if movement == "stop" {
            // El motor sigue frenando un instante: leer tambien la posicion final
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { self.pollZoomPosition() }
        }
    }

    /// Lee la posicion de zoom via getEvent. La API solo da porcentaje 0-100;
    /// los mm se estiman mapeando al rango 16-50 del PZ del kit.
    private func pollZoomPosition() {
        apiQueue.async {
            guard let r = try? SonyCamera.call("getEvent", params: [false]) else { return }
            for item in r {
                guard let object = item as? [String: Any],
                      let pct = (object["zoomPosition"] as? NSNumber)?.intValue else { continue }
                // Progresion geometrica: los zoom motorizados avanzan a ratio
                // constante, no a mm constantes (16-50 PZ)
                let mm = 16.0 * pow(50.0 / 16.0, Double(pct) / 100.0)
                let text = String(format: "Zoom %d%%  ~%.0f mm", pct, mm)
                DispatchQueue.main.async {
                    self.zoomText = text
                    self.zoomTextGeneration += 1
                    let generation = self.zoomTextGeneration
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                        if self.zoomTextGeneration == generation { self.zoomText = nil }
                    }
                }
                break
            }
        }
    }

    func takePicture() {
        if shootMode == "movie" {
            // En modo video el disparador inicia/detiene la grabacion
            let recording = movieRecording
            apiQueue.async {
                do {
                    try SonyCamera.call(recording ? "stopMovieRec" : "startMovieRec")
                    DispatchQueue.main.async { self.movieRecording = !recording }
                    self.showToast(recording ? "Recording stopped" : "Recording...")
                } catch {
                    self.showToast("Video: \(error.localizedDescription)")
                }
            }
            return
        }
        capturing = true
        apiQueue.async {
            defer { self.capturing = false }
            do {
                try SonyCamera.call("actTakePicture")
                self.showToast("Photo taken")
                self.refreshChips()  // los valores pueden cambiar tras el disparo (p.ej. ISO auto)
            } catch {
                self.showToast("Shutter: \(error.localizedDescription)")
            }
        }
    }

    /// Enfoque tactil: coordenadas en % del frame, ya des-rotadas por la vista.
    func touchFocus(xPct: Double, yPct: Double, marker: CGPoint) {
        focusMarker = marker
        focusGeneration += 1
        let generation = focusGeneration
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            if self.focusGeneration == generation { self.focusMarker = nil }
        }
        apiQueue.async {
            do {
                try SonyCamera.call("setTouchAFPosition", params: [xPct, yPct])
            } catch {
                self.showToast("Focus: \(error.localizedDescription)")
            }
        }
    }

    /// Actualiza las etiquetas de los chips con los valores actuales de la camara.
    func refreshChips() {
        apiQueue.async {
            var currentById: [String: String] = [:]
            for setting in Self.settings {
                if let result = try? SonyCamera.call(setting.currentMethod), let current = result.first {
                    currentById[setting.id] = Self.stringify(current)
                }
            }

            // getEvent es la instantanea de estado de la API de Sony. En
            // algunas camaras los getAvailable* solo devuelven un valor util
            // despues de haber cambiado el ajuste desde el remoto.
            if let events = try? SonyCamera.call("getEvent", params: [false], version: "1.1") {
                for case let event as [String: Any] in events {
                    let keys = [
                        "ISO": "currentIsoSpeedRate",
                        "Shutter": "currentShutterSpeed",
                        "Aperture": "currentFNumber",
                        "Focus": "currentFocusMode",
                        "Flash": "currentFlashMode",
                        "Timer": "currentSelfTimer",
                    ]
                    for (id, key) in keys where currentById[id] == nil {
                        if let value = event[key] { currentById[id] = Self.stringify(value) }
                    }
                }
            }
            for setting in Self.settings {
                if currentById[setting.id] == nil,
                   let r = try? SonyCamera.call(setting.getMethod), let current = r.first {
                    currentById[setting.id] = Self.stringify(current)
                }
            }
            let labels = Dictionary(uniqueKeysWithValues: Self.settings.compactMap { setting in
                currentById[setting.id].map { (setting.id, setting.chipLabel($0)) }
            })
            DispatchQueue.main.async { self.chipLabels.merge(labels) { _, new in new } }
            if let r = try? SonyCamera.call("getAvailableExposureCompensation"), r.count >= 4,
               let cur = (r[0] as? NSNumber)?.intValue,
               let stepIndex = (r[3] as? NSNumber)?.intValue {
                let stepEv = stepIndex == 2 ? 0.5 : 1.0 / 3.0
                let label = String(format: "%+.1f EV", Double(cur) * stepEv)
                DispatchQueue.main.async { self.chipLabels["EV"] = label }
            }
            if let r = try? SonyCamera.call("getAvailableWhiteBalance"),
               let mode = (r.first as? [String: Any])?["whiteBalanceMode"] as? String {
                DispatchQueue.main.async { self.chipLabels["WB"] = "WB \(Self.trimWbSuffix(mode))" }
            }
            if let r = try? SonyCamera.call("getAvailableShootMode"), let mode = r.first as? String {
                DispatchQueue.main.async {
                    self.shootMode = mode
                    self.chipLabels["MODE"] = mode == "movie" ? "Mode: Video" : "Mode: Photo"
                }
            }
        }
    }

    /// Sincroniza los tres valores que tambien pueden cambiar desde los
    /// controles fisicos. Solo permite una lectura pendiente para no formar
    /// cola si la camara tarda en responder.
    private func refreshExposureChips() {
        exposureRefreshLock.lock()
        guard !exposureRefreshPending else { exposureRefreshLock.unlock(); return }
        exposureRefreshPending = true
        exposureRefreshLock.unlock()

        apiQueue.async {
            defer {
                self.exposureRefreshLock.lock()
                self.exposureRefreshPending = false
                self.exposureRefreshLock.unlock()
            }

            let exposureSettings = Self.settings.filter {
                $0.id == "ISO" || $0.id == "Shutter" || $0.id == "Aperture"
            }
            var values: [String: String] = [:]

            if let events = try? SonyCamera.call("getEvent", params: [false], version: "1.1") {
                let keys = ["ISO": "currentIsoSpeedRate",
                            "Shutter": "currentShutterSpeed",
                            "Aperture": "currentFNumber"]
                for case let event as [String: Any] in events {
                    for (id, key) in keys where values[id] == nil {
                        if let value = event[key] { values[id] = Self.stringify(value) }
                    }
                }
            }

            for setting in exposureSettings where values[setting.id] == nil {
                if let r = try? SonyCamera.call(setting.currentMethod), let current = r.first {
                    values[setting.id] = Self.stringify(current)
                } else if let r = try? SonyCamera.call(setting.getMethod), let current = r.first {
                    values[setting.id] = Self.stringify(current)
                }
            }

            let labels = Dictionary(uniqueKeysWithValues: exposureSettings.compactMap { setting in
                values[setting.id].map { (setting.id, setting.chipLabel($0)) }
            })
            if !labels.isEmpty {
                DispatchQueue.main.async { self.chipLabels.merge(labels) { _, new in new } }
            }
        }
    }

    private static func trimWbSuffix(_ mode: String) -> String {
        mode.hasSuffix(" WB") ? String(mode.dropLast(3)) : mode
    }

    private func showToast(_ message: String) {
        DispatchQueue.main.async {
            self.toast = message
            self.toastGeneration += 1
            let generation = self.toastGeneration
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.2) {
                if self.toastGeneration == generation { self.toast = nil }
            }
        }
    }
}

// -- medidor de exposicion (calculado del liveview; la API de Sony no expone
//    el fotometro interno de la camara) --------------------------------------

enum ExposureMeter {

    private static let linearLut: [Float] = (0..<256).map { pow(Float($0) / 255, 2.2) }

    /// Reescala el frame a una muestra pequena y calcula histograma, EV y recorte.
    static func compute(_ image: CGImage) -> Exposure? {
        let width = 120
        let height = 68
        guard let ctx = CGContext(data: nil, width: width, height: height,
                                  bitsPerComponent: 8, bytesPerRow: width * 4,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
            return nil
        }
        ctx.interpolationQuality = .low
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let pixels = ctx.data?.assumingMemoryBound(to: UInt8.self) else { return nil }

        var hist = [Int](repeating: 0, count: 64)
        var sumLinear: Float = 0
        var shadows = 0
        var highlights = 0
        let count = width * height
        for i in 0..<count {
            let p = i * 4
            // Luma BT.709 en aritmetica entera
            let luma = (Int(pixels[p]) * 54 + Int(pixels[p + 1]) * 183 + Int(pixels[p + 2]) * 19) >> 8
            hist[luma >> 2] += 1
            sumLinear += linearLut[luma]
            if luma <= 4 { shadows += 1 } else if luma >= 251 { highlights += 1 }
        }
        let meanLinear = sumLinear / Float(count)
        // Desviacion respecto al gris medio (18% reflectancia)
        let ev = log2(meanLinear / 0.18)
        return Exposure(hist: hist, evOffset: ev,
                        clipShadows: Float(shadows) * 100 / Float(count),
                        clipHighlights: Float(highlights) * 100 / Float(count))
    }
}
