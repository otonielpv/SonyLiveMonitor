import SwiftUI
import UniformTypeIdentifiers

// -- galeria de la tarjeta via avContent (requiere Smart Remote parcheado) ----

struct GalleryOriginal: Hashable {
    let fileName: String
    let kind: String   // "jpeg" | "raw"
    let url: String
}

struct GalleryImage: Identifiable, Hashable {
    let created: String
    let thumbnail: String
    let preview: String
    let originals: [GalleryOriginal]

    var id: String { originals.first?.url ?? thumbnail + created }
    var jpeg: GalleryOriginal? { originals.first { $0.kind == "jpeg" } }
    var raw: GalleryOriginal? { originals.first { $0.kind == "raw" } }
}

struct DownloadState {
    var title = "Preparing download..."
    var detail = ""
    var percent: Double?   // nil = indeterminado
    var thumbKey = ""
}

enum DownloadKind { case jpeg, raw, both }

final class GalleryModel: ObservableObject {

    static let pageSize = 15

    // -- estado publicado (solo se toca en el hilo principal) ------------------

    @Published var images: [GalleryImage] = []
    @Published var thumbs: [String: UIImage] = [:]
    @Published var status = "Opening camera card..."
    @Published var busy = true
    @Published var canLoadMore = false
    @Published var selectMode = false
    @Published var selected: Set<GalleryImage> = []
    @Published var download: DownloadState?
    @Published var toast: String?
    @Published var viewerIndex: Int?
    @Published var customFolder = UserDefaults.standard.data(forKey: "download_bookmark") != nil

    private let apiQueue = DispatchQueue(label: "gallery-api")
    // Una sola miniatura cada vez: el servidor de la a6000 es muy limitado.
    private let thumbQueue = DispatchQueue(label: "gallery-thumbs")
    private var nextIndex = 0
    private var loadingPage = false
    private var closing = false
    private var toastGeneration = 0

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.allowsCellularAccess = false  // la camara solo es alcanzable por WiFi
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 600
        return URLSession(configuration: config)
    }()

    // -- apertura y paginado ----------------------------------------------------

    func open() {
        apiQueue.async {
            _ = try? SonyCamera.call("stopLiveview")
            var lastError: Error? = CameraError("Could not enable Contents Transfer")
            for _ in 0..<15 {
                do {
                    try SonyCamera.call("setCameraFunction", params: ["Contents Transfer"])
                    lastError = nil
                    break
                } catch {
                    lastError = error
                    Thread.sleep(forTimeInterval: 0.4)
                }
            }
            DispatchQueue.main.async {
                if let lastError {
                    self.busy = false
                    self.status = "Camera card unavailable"
                    self.showToast(lastError.localizedDescription)
                } else {
                    self.loadNextPage()
                }
            }
        }
    }

    func loadNextPage() {
        guard !loadingPage else { return }
        loadingPage = true
        busy = true
        status = "Reading camera index..."
        let start = nextIndex
        apiQueue.async {
            do {
                let request: [String: Any] = ["uri": "storage:memoryCard1", "stIdx": start,
                                              "cnt": Self.pageSize, "view": "flat", "sort": "descending"]
                let result = try SonyCamera.call("getContentList", params: [request],
                                                 endpoint: SonyCamera.contentEndpoint, version: "1.3")
                let items = result.first as? [[String: Any]] ?? []
                let parsed = Self.parse(items)
                DispatchQueue.main.async {
                    self.nextIndex += items.count
                    self.images.append(contentsOf: parsed)
                    self.busy = false
                    self.status = self.nextIndex == 0 ? "No photos found" : "\(self.nextIndex) entries ready"
                    self.canLoadMore = items.count == Self.pageSize
                    self.loadingPage = false
                    self.fetchThumbnails(parsed)
                }
            } catch {
                DispatchQueue.main.async {
                    self.busy = false
                    self.loadingPage = false
                    self.showToast("Page failed: \(error.localizedDescription)")
                }
            }
        }
    }

    private static func parse(_ items: [[String: Any]]) -> [GalleryImage] {
        items.compactMap { item in
            guard item["contentKind"] as? String == "still",
                  let content = item["content"] as? [String: Any] else { return nil }
            let originals: [GalleryOriginal] = (content["original"] as? [[String: Any]] ?? [])
                .compactMap { o in
                    guard let url = o["url"] as? String, !url.isEmpty else { return nil }
                    return GalleryOriginal(fileName: o["fileName"] as? String ?? "camera-file",
                                           kind: o["stillObject"] as? String ?? "", url: url)
                }
            var preview = content["largeUrl"] as? String ?? ""
            if preview.isEmpty { preview = originals.first { $0.kind == "jpeg" }?.url ?? "" }
            return GalleryImage(created: item["createdTime"] as? String ?? "",
                                thumbnail: content["thumbnailUrl"] as? String ?? "",
                                preview: preview, originals: originals)
        }
    }

    private func fetchThumbnails(_ batch: [GalleryImage]) {
        for image in batch where !image.thumbnail.isEmpty {
            thumbQueue.async {
                guard let data = try? Self.fetch(image.thumbnail),
                      let thumb = UIImage(data: data) else { return }
                DispatchQueue.main.async { self.thumbs[image.thumbnail] = thumb }
            }
        }
    }

    /// GET sincrono: usar siempre fuera del hilo principal.
    static func fetch(_ urlString: String) throws -> Data {
        guard let url = URL(string: urlString) else { throw CameraError("bad url: \(urlString)") }
        let semaphore = DispatchSemaphore(value: 0)
        var outcome: Result<Data, Error> = .failure(CameraError("no response"))
        session.dataTask(with: url) { data, response, error in
            if let error {
                outcome = .failure(error)
            } else if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                outcome = .failure(CameraError("HTTP \(http.statusCode)"))
            } else if let data {
                outcome = .success(data)
            }
            semaphore.signal()
        }.resume()
        semaphore.wait()
        return try outcome.get()
    }

    // -- seleccion ---------------------------------------------------------------

    func toggleSelectMode() {
        selectMode.toggle()
        if !selectMode { selected.removeAll() }
        if selectMode { status = "\(selected.count) selected" }
    }

    func toggleSelection(_ image: GalleryImage) {
        if selected.contains(image) { selected.remove(image) } else { selected.insert(image) }
        if selectMode { status = "\(selected.count) selected" }
    }

    // -- descargas ----------------------------------------------------------------

    /// Igual que Android: al elegir RAW, las fotos sin RAW se omiten; solo si
    /// ninguna tiene el formato pedido se avisa al usuario.
    func downloadSelected(_ kind: DownloadKind) {
        let ordered = images.filter { selected.contains($0) }
        let jobs: [(GalleryImage, GalleryOriginal)] = ordered.flatMap { image in
            image.originals.filter {
                switch kind {
                case .jpeg: return $0.kind == "jpeg"
                case .raw: return $0.kind == "raw"
                case .both: return $0.kind == "jpeg" || $0.kind == "raw"
                }
            }.map { (image, $0) }
        }
        if jobs.isEmpty {
            showToast("The selected photos do not contain that format")
        } else {
            enqueue(jobs)
        }
    }

    func enqueue(_ jobs: [(GalleryImage, GalleryOriginal)]) {
        guard download == nil else { return }
        if selectMode { toggleSelectMode() }
        download = DownloadState()
        Task {
            for (i, job) in jobs.enumerated() {
                do {
                    try await self.downloadOne(job.0, job.1, position: i + 1, total: jobs.count)
                } catch {
                    self.showToast("\(job.1.fileName): \(error.localizedDescription)")
                }
            }
            await MainActor.run {
                self.download = nil
                self.status = "Downloads finished"
                self.showToast("Saved to \(self.destinationDescription)")
            }
        }
    }

    private func downloadOne(_ image: GalleryImage, _ original: GalleryOriginal,
                             position: Int, total: Int) async throws {
        await MainActor.run {
            self.download = DownloadState(title: original.fileName, detail: "\(position) of \(total)",
                                          percent: nil, thumbKey: image.thumbnail)
        }
        guard let url = URL(string: original.url) else { throw CameraError("bad url") }
        let (bytes, response) = try await Self.session.bytes(from: url)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw CameraError("HTTP \(http.statusCode)")
        }
        let length = response.expectedContentLength

        let (dir, scoped) = try destinationDirectory()
        defer { if scoped { dir.stopAccessingSecurityScopedResource() } }
        let dest = dir.appendingPathComponent(original.fileName)
        FileManager.default.createFile(atPath: dest.path, contents: nil)
        let handle = try FileHandle(forWritingTo: dest)
        defer { try? handle.close() }

        var buffer = Data()
        buffer.reserveCapacity(128 * 1024)
        var copied: Int64 = 0
        var lastPercent = -1
        for try await byte in bytes {
            buffer.append(byte)
            if buffer.count >= 128 * 1024 {
                try handle.write(contentsOf: buffer)
                copied += Int64(buffer.count)
                buffer.removeAll(keepingCapacity: true)
                if length > 0 {
                    let percent = Int(copied * 100 / length)
                    if percent != lastPercent {
                        lastPercent = percent
                        await MainActor.run {
                            self.download?.percent = Double(percent) / 100
                            self.download?.detail = "\(position) of \(total) · \(percent)%"
                        }
                    }
                }
            }
        }
        try handle.write(contentsOf: buffer)
    }

    // -- destino de guardado ---------------------------------------------------------

    /// Carpeta elegida por el usuario (bookmark con permiso persistente) o, por
    /// defecto, Documents/SonyLiveMonitor — visible en la app Archivos.
    private func destinationDirectory() throws -> (URL, Bool) {
        if let data = UserDefaults.standard.data(forKey: "download_bookmark") {
            var stale = false
            if let url = try? URL(resolvingBookmarkData: data, bookmarkDataIsStale: &stale),
               url.startAccessingSecurityScopedResource() {
                return (url, true)
            }
        }
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = docs.appendingPathComponent("SonyLiveMonitor", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return (dir, false)
    }

    var destinationDescription: String {
        customFolder ? "the selected folder" : "Files > Sony Live Monitor"
    }

    func setFolder(_ url: URL) {
        guard url.startAccessingSecurityScopedResource() else {
            showToast("Cannot access that folder")
            return
        }
        defer { url.stopAccessingSecurityScopedResource() }
        if let data = try? url.bookmarkData() {
            UserDefaults.standard.set(data, forKey: "download_bookmark")
            customFolder = true
        }
    }

    func clearFolder() {
        UserDefaults.standard.removeObject(forKey: "download_bookmark")
        customFolder = false
    }

    // -- cierre -------------------------------------------------------------------

    func close(_ dismiss: @escaping () -> Void) {
        guard !closing else { return }
        if download != nil {
            showToast("Wait for downloads to finish")
            return
        }
        closing = true
        busy = true
        status = "Returning to remote shooting..."
        // Igual que Android: si la camara no responde, salir de todos modos.
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5, execute: dismiss)
        apiQueue.async {
            _ = try? SonyCamera.call("setCameraFunction", params: ["Remote Shooting"])
            DispatchQueue.main.async(execute: dismiss)
        }
    }

    func showToast(_ message: String) {
        DispatchQueue.main.async {
            self.toast = message
            self.toastGeneration += 1
            let generation = self.toastGeneration
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                if self.toastGeneration == generation { self.toast = nil }
            }
        }
    }
}

// -- vista principal --------------------------------------------------------------

struct CameraGalleryView: View {
    @StateObject private var model = GalleryModel()
    let onClose: () -> Void

    init(onClose: @escaping () -> Void) {
        self.onClose = onClose
    }

    @State private var askFormat = false
    @State private var askFolder = false
    @State private var showFolderPicker = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                header
                actions
                if model.canLoadMore {
                    Button("Load \(GalleryModel.pageSize) more") { model.loadNextPage() }
                        .frame(maxWidth: .infinity, minHeight: 38)
                        .background(Color(white: 0.14))
                        .foregroundColor(.white)
                }
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: 4)], spacing: 4) {
                        ForEach(Array(model.images.enumerated()), id: \.element.id) { index, image in
                            cell(image, index: index)
                        }
                    }
                    .padding(4)
                }
            }
            if let index = model.viewerIndex, model.images.indices.contains(index) {
                GalleryViewer(model: model, index: index)
            }
            // Modal centrado: cubre y bloquea toda la pantalla hasta terminar.
            if let state = model.download {
                downloadModal(state)
            }
            if let toast = model.toast {
                Text(toast)
                    .font(.system(size: 13))
                    .foregroundColor(.white)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(Capsule().fill(Color.black.opacity(0.75)))
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    .padding(.bottom, 40)
                    .allowsHitTesting(false)
            }
        }
        .statusBarHidden(true)
        .preferredColorScheme(.dark)
        .onAppear { model.open() }
        .confirmationDialog("Files to download", isPresented: $askFormat, titleVisibility: .visible) {
            Button("JPEG") { model.downloadSelected(.jpeg) }
            Button("RAW") { model.downloadSelected(.raw) }
            Button("JPEG + RAW") { model.downloadSelected(.both) }
        }
        .confirmationDialog("Saving destination", isPresented: $askFolder, titleVisibility: .visible) {
            Button(model.customFolder ? "Choose another folder" : "Choose folder") {
                showFolderPicker = true
            }
            if model.customFolder {
                Button("Use Files > Sony Live Monitor") { model.clearFolder() }
            }
        }
        .fileImporter(isPresented: $showFolderPicker, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result { model.setFolder(url) }
        }
    }

    private var header: some View {
        HStack {
            Button("Back") { model.close(onClose) }
                .foregroundColor(.white)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 7).fill(Color(white: 0.14)))
            Text(model.status)
                .font(.system(size: 15))
                .foregroundColor(.white)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 8)
            if model.busy { ProgressView().tint(.white) }
        }
        .padding(10)
    }

    private var actions: some View {
        HStack(spacing: 6) {
            Button(model.selectMode ? "Cancel" : "Select") { model.toggleSelectMode() }
                .frame(maxWidth: .infinity)
            Button(model.selected.isEmpty ? "Download" : "Download (\(model.selected.count))") {
                askFormat = true
            }
            .frame(maxWidth: .infinity)
            .disabled(model.selected.isEmpty)
            Button(model.customFolder ? "Folder: custom" : "Folder: default") { askFolder = true }
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .tint(.white)
        .padding(.horizontal, 10).padding(.bottom, 8)
    }

    private func cell(_ image: GalleryImage, index: Int) -> some View {
        GeometryReader { geo in
            ZStack(alignment: .topTrailing) {
                if let thumb = model.thumbs[image.thumbnail] {
                    Image(uiImage: thumb)
                        .resizable()
                        .scaledToFill()
                        .frame(width: geo.size.width, height: geo.size.height)
                        .clipped()
                } else {
                    Color(white: 0.2)
                }
                if model.selected.contains(image) {
                    Text("✓")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 27, height: 27)
                        .background(Color(red: 0, green: 0.57, blue: 1))
                }
            }
            .contentShape(Rectangle())
            .onTapGesture {
                if model.selectMode {
                    model.toggleSelection(image)
                } else {
                    model.viewerIndex = index
                }
            }
            .onLongPressGesture {
                if !model.selectMode { model.toggleSelectMode() }
                model.toggleSelection(image)
            }
        }
        .aspectRatio(1, contentMode: .fit)
    }

    private func downloadModal(_ state: DownloadState) -> some View {
        ZStack {
            Color.black.opacity(0.55).ignoresSafeArea()
            HStack(spacing: 14) {
                Group {
                    if let thumb = model.thumbs[state.thumbKey] {
                        Image(uiImage: thumb).resizable().scaledToFill()
                    } else {
                        Color(white: 0.25)
                    }
                }
                .frame(width: 64, height: 64)
                .clipped()
                VStack(alignment: .leading, spacing: 6) {
                    Text(state.title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.white)
                        .lineLimit(1)
                    Text(state.detail)
                        .font(.system(size: 13))
                        .foregroundColor(.gray)
                    if let percent = state.percent {
                        ProgressView(value: percent).tint(.white)
                    } else {
                        ProgressView().tint(.white)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(20)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color(white: 0.12)))
            .frame(maxWidth: 360)
            .padding(.horizontal, 24)
        }
    }
}

// -- visor a pantalla completa: zoom, arrastre y swipe entre fotos ------------------

private struct GalleryViewer: View {
    @ObservedObject var model: GalleryModel
    let index: Int

    @State private var preview: UIImage?
    @State private var loading = true
    @State private var zoom: CGFloat = 1
    @State private var zoomBase: CGFloat = 1
    @State private var pan: CGSize = .zero
    @State private var panBase: CGSize = .zero

    private var image: GalleryImage { model.images[index] }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            Group {
                if let shown = preview ?? model.thumbs[image.thumbnail] {
                    Image(uiImage: shown)
                        .resizable()
                        .scaledToFit()
                        .scaleEffect(zoom)
                        .offset(pan)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
            .clipped()
            .gesture(magnify.simultaneously(with: drag))
            if loading {
                ProgressView().tint(.white)
            }
            VStack {
                Text(image.jpeg?.fileName ?? image.raw?.fileName ?? "Camera photo")
                    .font(.system(size: 16))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Color.black.opacity(0.7))
                Spacer()
                HStack(spacing: 8) {
                    Button("Close") { model.viewerIndex = nil }
                    if let jpeg = image.jpeg {
                        Button("Save JPEG") { model.enqueue([(image, jpeg)]) }
                    }
                    if let raw = image.raw {
                        Button("Save RAW") { model.enqueue([(image, raw)]) }
                    }
                }
                .buttonStyle(.bordered)
                .tint(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(Color.black.opacity(0.7))
            }
        }
        // task(id:) recarga el preview al cambiar de foto y cancela el anterior,
        // asi un preview lento nunca pisa la foto a la que ya se ha movido.
        .task(id: index) {
            zoom = 1; zoomBase = 1; pan = .zero; panBase = .zero
            preview = nil
            let item = model.images[index]
            guard !item.preview.isEmpty else { loading = false; return }
            loading = true
            let data = await Task.detached { try? GalleryModel.fetch(item.preview) }.value
            guard !Task.isCancelled else { return }
            if let data, let ui = UIImage(data: data) { preview = ui }
            loading = false
        }
    }

    private var magnify: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                zoom = min(max(zoomBase * value, 1), 6)
                if zoom == 1 { pan = .zero; panBase = .zero }
            }
            .onEnded { _ in zoomBase = zoom }
    }

    private var drag: some Gesture {
        DragGesture()
            .onChanged { g in
                guard zoom > 1 else { return }
                pan = CGSize(width: panBase.width + g.translation.width,
                             height: panBase.height + g.translation.height)
            }
            .onEnded { g in
                if zoom > 1 { panBase = pan; return }
                let dx = g.translation.width
                let dy = g.translation.height
                guard abs(dx) > 80, abs(dx) > abs(dy) * 2 else { return }
                let next = index + (dx < 0 ? 1 : -1)
                if model.images.indices.contains(next) { model.viewerIndex = next }
            }
    }
}
