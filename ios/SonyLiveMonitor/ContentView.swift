import SwiftUI

private let accentGreen = Color(red: 0, green: 1, blue: 0.5)
private let alertRed = Color(red: 1, green: 0.3, blue: 0.3)
private let warnAmber = Color(red: 1, green: 0.78, blue: 0)

/// Color del indicador de salud del enlace: 1=good verde, 2=fair ambar, 3=weak rojo.
private func linkColor(_ quality: Int) -> Color {
    switch quality {
    case 1: return accentGreen
    case 2: return warnAmber
    default: return alertRed
    }
}

struct ContentView: View {
    @StateObject private var model = MonitorViewModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        GeometryReader { geo in
            let portrait = geo.size.height >= geo.size.width
            ZStack {
                Color.black.ignoresSafeArea()
                if portrait {
                    VStack(spacing: 0) {
                        VideoArea(model: model)
                        ControlPanel(model: model, columns: 4, scrollable: false)
                    }
                } else {
                    HStack(spacing: 0) {
                        VideoArea(model: model)
                        ControlPanel(model: model, columns: 2, scrollable: true)
                            .frame(width: 300)
                    }
                }
                ShutterOverlay(model: model, containerSize: geo.size, portrait: portrait)
                if let toast = model.toast {
                    Text(toast)
                        .font(.system(size: 13))
                        .foregroundColor(.white)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(Capsule().fill(Color.black.opacity(0.75)))
                        .frame(maxHeight: .infinity, alignment: .bottom)
                        .padding(.bottom, portrait ? geo.size.height * 0.35 : 40)
                        .allowsHitTesting(false)
                }
            }
        }
        .background(Color.black.ignoresSafeArea())
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .preferredColorScheme(.dark)
        .onAppear { model.start() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                model.start()
            } else if phase == .background {
                model.stop()
            }
        }
        .sheet(isPresented: $model.showConnectHelp) { ConnectHelpView() }
        .sheet(isPresented: Binding(
            get: { model.diagnosticsReport != nil },
            set: { if !$0 { model.diagnosticsReport = nil } }
        )) {
            DiagnosticsView(report: model.diagnosticsReport ?? "")
        }
        .fullScreenCover(isPresented: $model.showGallery, onDismiss: { model.start() }) {
            CameraGalleryView { model.showGallery = false }
        }
    }
}

// -- area de video -----------------------------------------------------------------

/// Caja que ocupa el frame en pantalla tras encajar y rotar.
/// Devuelve el tamano pre-rotacion (para el frame de la Image) y la caja final.
private func videoBox(container: CGSize, image: CGSize, rotation: Int) -> (size: CGSize, box: CGRect) {
    guard image.width > 0, image.height > 0, container.width > 0, container.height > 0 else {
        return (.zero, .zero)
    }
    // Con 90/270 grados el encaje se calcula con los ejes intercambiados
    let swap = rotation % 180 != 0
    let availW = swap ? container.height : container.width
    let availH = swap ? container.width : container.height
    let scale = min(availW / image.width, availH / image.height)
    let w = image.width * scale
    let h = image.height * scale
    let boxW = swap ? h : w
    let boxH = swap ? w : h
    let box = CGRect(x: (container.width - boxW) / 2, y: (container.height - boxH) / 2,
                     width: boxW, height: boxH)
    return (CGSize(width: w, height: h), box)
}

struct VideoArea: View {
    @ObservedObject var model: MonitorViewModel

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                Color.black
                if let image = model.image, model.statusMessage == nil {
                    let fit = videoBox(container: geo.size, image: image.size, rotation: model.rotation)
                    Image(uiImage: image)
                        .resizable()
                        .interpolation(.low)
                        .frame(width: fit.size.width, height: fit.size.height)
                        .rotationEffect(.degrees(Double(model.rotation)))
                        .scaleEffect(x: model.mirror ? -1 : 1, y: 1)
                        .position(x: geo.size.width / 2, y: geo.size.height / 2)
                    if let peaking = model.peakingImage {
                        Image(uiImage: peaking)
                            .resizable()
                            .interpolation(.none)
                            .frame(width: fit.size.width, height: fit.size.height)
                            .rotationEffect(.degrees(Double(model.rotation)))
                            .scaleEffect(x: model.mirror ? -1 : 1, y: 1)
                            .position(x: geo.size.width / 2, y: geo.size.height / 2)
                            .allowsHitTesting(false)
                    }
                    GridOverlay(mode: model.grid, rect: fit.box)
                        .allowsHitTesting(false)
                    if let marker = model.focusMarker {
                        Rectangle()
                            .stroke(Color(red: 1, green: 0.78, blue: 0), lineWidth: 2)
                            .frame(width: 56, height: 56)
                            .position(marker)
                            .allowsHitTesting(false)
                    }
                    if let exposure = model.exposure {
                        MeterView(exposure: exposure)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                            .padding(10)
                            .allowsHitTesting(false)
                    }
                }
                VStack(alignment: .leading, spacing: 6) {
                    if model.hudOn, model.statusMessage == nil, let hud = model.hudText {
                        HudLabel(text: hud, color: accentGreen, size: 13)
                    }
                    if model.hudOn, model.statusMessage == nil, let link = model.linkText {
                        HudLabel(text: link, color: linkColor(model.linkQuality), size: 12)
                    }
                    if let alert = model.alertText {
                        HudLabel(text: alert, color: alertRed, size: 17)
                    }
                    if let zoom = model.zoomText {
                        HudLabel(text: zoom, color: accentGreen, size: 13)
                    }
                    if let message = model.statusMessage {
                        Text(message)
                            .font(.system(size: 16, weight: .semibold, design: .monospaced))
                            .foregroundColor(alertRed)
                            .lineSpacing(4)
                            .padding(.top, 24)
                    }
                }
                .padding(12)
                .allowsHitTesting(false)
            }
            .contentShape(Rectangle())
            .onTapGesture(coordinateSpace: .local) { point in
                handleTap(point, container: geo.size)
            }
        }
        .clipped()
    }

    private func handleTap(_ point: CGPoint, container: CGSize) {
        if model.strip != nil {
            model.closeStrip()  // tocar la imagen cierra la tira de valores
            return
        }
        guard let image = model.image else { return }
        let box = videoBox(container: container, image: image.size, rotation: model.rotation).box
        guard !box.isEmpty, box.contains(point) else { return }
        // Deshacer primero el espejo de pantalla y despues la rotacion del render.
        var u = (point.x - box.minX) / box.width
        let w = (point.y - box.minY) / box.height
        if model.mirror { u = 1 - u }
        let fx: CGFloat
        let fy: CGFloat
        switch model.rotation {
        case 90: (fx, fy) = (w, 1 - u)
        case 180: (fx, fy) = (1 - u, 1 - w)
        case 270: (fx, fy) = (1 - w, u)
        default: (fx, fy) = (u, w)
        }
        model.touchFocus(xPct: Double(fx * 100), yPct: Double(fy * 100), marker: point)
    }
}

struct HudLabel: View {
    let text: String
    let color: Color
    let size: CGFloat

    var body: some View {
        Text(text)
            .font(.system(size: size, weight: .bold, design: .monospaced))
            .foregroundColor(color)
            .shadow(color: .black, radius: 1, x: 1, y: 1)
            .shadow(color: .black, radius: 2)
    }
}

struct GridOverlay: View {
    let mode: GridMode
    let rect: CGRect

    var body: some View {
        Canvas { ctx, _ in
            guard mode != .off, !rect.isEmpty else { return }
            var path = Path()
            switch mode {
            case .off:
                break
            case .thirds, .thirdsDiag:
                for i in 1...2 {
                    let x = rect.minX + rect.width * CGFloat(i) / 3
                    let y = rect.minY + rect.height * CGFloat(i) / 3
                    path.move(to: CGPoint(x: x, y: rect.minY))
                    path.addLine(to: CGPoint(x: x, y: rect.maxY))
                    path.move(to: CGPoint(x: rect.minX, y: y))
                    path.addLine(to: CGPoint(x: rect.maxX, y: y))
                }
                if mode == .thirdsDiag {
                    path.move(to: CGPoint(x: rect.minX, y: rect.minY))
                    path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
                    path.move(to: CGPoint(x: rect.minX, y: rect.maxY))
                    path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
                }
            case .cross:
                path.move(to: CGPoint(x: rect.midX, y: rect.minY))
                path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
                path.move(to: CGPoint(x: rect.minX, y: rect.midY))
                path.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
                let radius = min(rect.width, rect.height) * 0.04
                path.addEllipse(in: CGRect(x: rect.midX - radius, y: rect.midY - radius,
                                           width: radius * 2, height: radius * 2))
            }
            ctx.stroke(path, with: .color(.white.opacity(0.55)), lineWidth: 1)
        }
    }
}

struct MeterView: View {
    let exposure: Exposure

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Canvas { ctx, size in
                let maxCount = max(exposure.hist.max() ?? 1, 1)
                let barW = size.width / CGFloat(exposure.hist.count)
                for (i, count) in exposure.hist.enumerated() {
                    let barH = size.height * CGFloat(count) / CGFloat(maxCount)
                    let bar = CGRect(x: CGFloat(i) * barW, y: size.height - barH,
                                     width: max(barW - 0.5, 0.5), height: barH)
                    ctx.fill(Path(bar), with: .color(.white.opacity(0.85)))
                }
            }
            .frame(width: 190, height: 56)
            HStack(spacing: 10) {
                Text(String(format: "%+.1f EV", exposure.evOffset))
                    .foregroundColor(.white)
                Text(String(format: "low %.0f%%  high %.0f%%",
                            exposure.clipShadows, exposure.clipHighlights))
                    .foregroundColor(exposure.clipShadows > 20 || exposure.clipHighlights > 5
                                     ? alertRed : .white)
            }
            .font(.system(size: 11, weight: .semibold, design: .monospaced))
        }
        .padding(8)
        .background(Color.black.opacity(0.5))
    }
}

// -- panel de control ----------------------------------------------------------------

struct ControlPanel: View {
    @ObservedObject var model: MonitorViewModel
    let columns: Int
    let scrollable: Bool

    var body: some View {
        if scrollable {
            ScrollView(showsIndicators: false) { content }
                .background(Color.black.opacity(0.85))
        } else {
            content
                .background(Color.black.opacity(0.85))
        }
    }

    private var content: some View {
        let largestGroupItems = MonitorViewModel.settings.count + 5  // EV, WB, mode y dos zooms
        let reservedRows = (largestGroupItems + columns - 1) / columns
        VStack(spacing: 0) {
            if let strip = model.strip {
                ValueStripView(strip: strip)
            }
            HStack(spacing: 4) {
                ForEach(Array(["Camera", "View", "App"].enumerated()), id: \.offset) { index, title in
                    PanelTabButton(label: title, active: model.panelTab == index) {
                        model.selectPanelTab(index)
                    }
                }
            }
            .padding(.horizontal, 6)
            .padding(.top, 6)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: columns),
                      spacing: 4) {
                switch model.panelTab {
                case 0:
                    ForEach(MonitorViewModel.settings) { setting in
                        ChipButton(label: model.chipLabels[setting.id] ?? setting.id,
                                   active: model.strip?.owner == setting.id) {
                            model.toggleStrip(for: setting)
                        }
                    }
                    ChipButton(label: model.chipLabels["EV"] ?? "EV",
                               active: model.strip?.owner == "EV") { model.toggleEvStrip() }
                    ChipButton(label: model.chipLabels["WB"] ?? "WB",
                               active: model.strip?.owner == "WB") { model.toggleWbStrip() }
                    ChipButton(label: model.chipLabels["MODE"] ?? "Mode",
                               active: model.strip?.owner == "MODE") { model.toggleModeStrip() }
                    ZoomChip(label: "Z− (W)", direction: "out", model: model)
                    ZoomChip(label: "Z+ (T)", direction: "in", model: model)
                case 1:
                    ChipButton(label: "Rot: \(model.rotation)°") { model.cycleRotation() }
                    ChipButton(label: "Mirror: \(model.mirror ? "on" : "off")",
                               active: model.mirror) { model.toggleMirror() }
                    ChipButton(label: "Grid: \(model.grid.label)", active: model.grid != .off) {
                        model.cycleGrid()
                    }
                    ChipButton(label: "Meter: \(model.meterOn ? "on" : "off")",
                               active: model.meterOn) { model.toggleMeter() }
                    ChipButton(label: "Peak: \(model.peakingColor.label)",
                               active: model.peakingColor != .off) { model.cyclePeakingColor() }
                    ChipButton(label: "Peak sens: \(model.peakingSensitivity.shortLabel)") {
                        model.cyclePeakingSensitivity()
                    }
                    ChipButton(label: "HUD: \(model.hudOn ? "on" : "off")",
                               active: model.hudOn) { model.toggleHud() }
                default:
                    ChipButton(label: "WiFi") { model.showConnectHelp = true }
                    ChipButton(label: "Camera card") { model.openGallery() }
                    ChipButton(label: "Diagnostics") { model.runDiagnostics() }
                }
            }
            .frame(height: CGFloat(reservedRows * 42 - 4), alignment: .top)
            .padding(6)
        }
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(Color(red: 0.06, green: 0.075, blue: 0.095).opacity(0.96))
        )
    }
}

struct PanelTabButton: View {
    let label: String
    let active: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 13, weight: active ? .bold : .medium))
                .foregroundColor(active ? accentGreen : .white.opacity(0.75))
                .frame(maxWidth: .infinity, minHeight: 34)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(active ? Color(red: 0.08, green: 0.23, blue: 0.18) : .clear)
                        .overlay(
                            RoundedRectangle(cornerRadius: 10)
                                .stroke(active ? accentGreen.opacity(0.9) : .clear, lineWidth: 1)
                        )
                )
        }
        .buttonStyle(.plain)
    }
}

struct ChipButton: View {
    let label: String
    var active = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 12, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .foregroundColor(active ? Color(red: 0.3, green: 1, blue: 0.64) : .white.opacity(0.88))
                .frame(maxWidth: .infinity, minHeight: 38, maxHeight: 38)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(active
                              ? Color(red: 0.08, green: 0.23, blue: 0.18)
                              : Color(red: 0.12, green: 0.14, blue: 0.17))
                        .overlay(
                            RoundedRectangle(cornerRadius: 10)
                                .stroke(active ? accentGreen.opacity(0.85) : Color.white.opacity(0.12),
                                        lineWidth: 1)
                        )
                )
        }
        .buttonStyle(.plain)
    }
}

/// Chip de zoom motorizado: mantener pulsado para mover, soltar para parar.
struct ZoomChip: View {
    let label: String
    let direction: String
    @ObservedObject var model: MonitorViewModel
    @State private var pressing = false

    var body: some View {
        Text(label)
            .font(.system(size: 12, weight: .semibold))
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .foregroundColor(pressing ? accentGreen : .white.opacity(0.88))
            .frame(maxWidth: .infinity, minHeight: 38, maxHeight: 38)
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(pressing
                          ? Color(red: 0.08, green: 0.23, blue: 0.18)
                          : Color(red: 0.12, green: 0.14, blue: 0.17))
                    .overlay(
                        RoundedRectangle(cornerRadius: 10)
                            .stroke(pressing ? accentGreen.opacity(0.85) : Color.white.opacity(0.12),
                                    lineWidth: 1)
                    )
            )
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        if !pressing {
                            pressing = true
                            model.zoom(direction: direction, movement: "start")
                        }
                    }
                    .onEnded { _ in
                        pressing = false
                        model.zoom(direction: direction, movement: "stop")
                    }
            )
    }
}

/// Tira horizontal de valores: cada toque aplica al instante y la tira sigue abierta.
struct ValueStripView: View {
    let strip: ValueStrip

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    if strip.values.isEmpty {
                        ProgressView()
                            .tint(.white)
                            .padding(.vertical, 10)
                    }
                    ForEach(strip.values, id: \.self) { value in
                        let selected = value == strip.current
                        Button {
                            strip.apply(value)
                        } label: {
                            Text(value)
                                .font(.system(size: 13, weight: selected ? .bold : .regular))
                                .foregroundColor(selected ? accentGreen : .white)
                                .padding(.horizontal, 14).padding(.vertical, 8)
                                .background(
                                    Capsule()
                                        .fill(Color(red: 0.15, green: 0.16, blue: 0.20))
                                        .overlay(Capsule().stroke(accentGreen.opacity(selected ? 0.9 : 0.4),
                                                                  lineWidth: 1))
                                )
                        }
                        .buttonStyle(.plain)
                        .id(value)
                    }
                }
                .padding(.horizontal, 8).padding(.vertical, 6)
            }
            .background(Color.black.opacity(0.6))
            .onAppear {
                if let current = strip.current { proxy.scrollTo(current, anchor: .center) }
            }
        }
    }
}

// -- disparador flotante ----------------------------------------------------------------

/// Disparador flotante y arrastrable: toque corto dispara, arrastrar lo recoloca.
/// La posicion se recuerda por orientacion (vertical/horizontal).
struct ShutterOverlay: View {
    @ObservedObject var model: MonitorViewModel
    let containerSize: CGSize
    let portrait: Bool

    @State private var position: CGPoint?
    @State private var dragStart: CGPoint?
    @State private var dragged = false

    private var key: String { portrait ? "shutter_port" : "shutter_land" }
    private let diameter: CGFloat = 64

    var body: some View {
        let pos = clamp(position ?? savedOrDefault())
        Text(model.shootMode == "movie" && model.movieRecording ? "■" : "●")
            .font(.system(size: 26))
            .foregroundColor(alertRed)
            .frame(width: diameter, height: diameter)
            .background(
                Circle()
                    .fill(Color.black.opacity(0.45))
                    .overlay(Circle().stroke(Color.white.opacity(0.35), lineWidth: 1.5))
            )
            .position(pos)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        if dragStart == nil {
                            dragStart = pos
                            dragged = false
                        }
                        let dx = g.translation.width
                        let dy = g.translation.height
                        if dragged || abs(dx) > 24 || abs(dy) > 24 {
                            dragged = true
                            position = clamp(CGPoint(x: dragStart!.x + dx, y: dragStart!.y + dy))
                        }
                    }
                    .onEnded { _ in
                        if dragged, let p = position {
                            UserDefaults.standard.set(Double(p.x), forKey: "\(key)_x")
                            UserDefaults.standard.set(Double(p.y), forKey: "\(key)_y")
                        } else {
                            model.takePicture()
                        }
                        dragStart = nil
                        dragged = false
                    }
            )
            .onChange(of: portrait) { _, _ in position = nil }
    }

    private func savedOrDefault() -> CGPoint {
        let d = UserDefaults.standard
        if let x = d.object(forKey: "\(key)_x") as? Double,
           let y = d.object(forKey: "\(key)_y") as? Double {
            return CGPoint(x: x, y: y)
        }
        return CGPoint(x: containerSize.width - diameter / 2 - 16, y: containerSize.height / 2)
    }

    private func clamp(_ p: CGPoint) -> CGPoint {
        let r = diameter / 2
        return CGPoint(x: min(max(p.x, r), max(containerSize.width - r, r)),
                       y: min(max(p.y, r), max(containerSize.height - r, r)))
    }
}

// -- ayuda de conexion --------------------------------------------------------------------

struct ConnectHelpView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("iOS does not let apps join a WiFi network by themselves, so it is a one-time manual step:")
                    Label("On the camera, open **Ctrl w/ Smartphone** (MENU > Application). The screen shows the SSID and password.",
                          systemImage: "camera")
                    Label("On this iPhone, open **Settings > WiFi** and join the **DIRECT-xxxx:ILCE-6000** network with that password.",
                          systemImage: "wifi")
                    Label("Come back to this app: it connects and reconnects automatically.",
                          systemImage: "arrow.clockwise")
                    Label("The first time, iOS will ask permission to find devices on the local network — allow it, or the camera will be unreachable.",
                          systemImage: "exclamationmark.triangle")
                }
                .padding()
            }
            .navigationTitle("Connect to camera")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

// -- diagnostico de camara ----------------------------------------------------------------

/// Informe legible de la camara conectada, pensado para que los testers lo
/// compartan cuando prueban un modelo no verificado.
struct DiagnosticsView: View {
    let report: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(report)
                    .font(.system(size: 12, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
            }
            .navigationTitle("Camera diagnostics")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    ShareLink(item: report) { Image(systemName: "square.and.arrow.up") }
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

#Preview {
    ContentView()
}
