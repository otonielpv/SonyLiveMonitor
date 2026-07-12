import Foundation

struct CameraError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Cliente minimo de la Sony Camera Remote API (JSON-RPC sobre HTTP).
enum SonyCamera {

    static let defaultEndpoint = "http://192.168.122.1:8080/sony/camera"
    static let contentEndpoint = "http://192.168.122.1:8080/sony/avContent"

    private static let idLock = NSLock()
    private static var nextId = 0

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 10
        config.allowsCellularAccess = false  // la camara solo es alcanzable por WiFi
        return URLSession(configuration: config)
    }()

    /// Llamada JSON-RPC sincrona: usar siempre fuera del hilo principal.
    @discardableResult
    static func call(_ method: String, params: [Any] = [], endpoint: String = defaultEndpoint,
                     version: String = "1.0") throws -> [Any] {
        idLock.lock()
        nextId += 1
        let id = nextId
        idLock.unlock()

        let payload: [String: Any] = ["method": method, "params": params, "id": id, "version": version]
        guard let url = URL(string: endpoint) else { throw CameraError("bad endpoint: \(endpoint)") }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 10
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let semaphore = DispatchSemaphore(value: 0)
        var outcome: Result<Data, Error> = .failure(CameraError("\(method): no response"))
        session.dataTask(with: request) { data, _, error in
            if let error {
                outcome = .failure(error)
            } else if let data {
                outcome = .success(data)
            }
            semaphore.signal()
        }.resume()
        semaphore.wait()

        let data = try outcome.get()
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CameraError("\(method): invalid response")
        }
        if let error = json["error"] as? [Any], error.count >= 2 {
            throw CameraError("\(method) failed: [\(error[0])] \(error[1])")
        }
        if let result = json["result"] as? [Any] { return result }
        if let result = json["results"] as? [Any] { return result }
        throw CameraError("\(method): no result")
    }

    /// Informe legible para compartir desde la app: con esto un tester con un
    /// modelo no probado nos dice exactamente que soporta su camara.
    /// Sincrono: llamar fuera del hilo principal.
    static func diagnostics() -> String {
        func probe(_ method: String, endpoint: String = defaultEndpoint, version: String = "1.0") -> String {
            do { return try String(describing: call(method, endpoint: endpoint, version: version)) }
            catch { return "ERROR: \(error.localizedDescription)" }
        }

        var api: [String] = []
        if let list = try? call("getAvailableApiList").first as? [String] {
            api = list.sorted()
        }

        var out = "SonyLiveMonitor diagnostics (iOS)\n"
        out += "Camera endpoint: \(defaultEndpoint)\n"
        out += "getVersions: \(probe("getVersions"))\n"
        out += "getApplicationInfo: \(probe("getApplicationInfo"))\n"
        out += "avContent getSchemeList: \(probe("getSchemeList", endpoint: contentEndpoint))\n"
        out += "getAvailableApiList (\(api.count)):\n"
        for method in api { out += "- \(method)\n" }
        return out
    }

    /// Prepara la camara y devuelve la URL del stream de liveview.
    static func startLiveview(endpoint: String = defaultEndpoint) throws -> String {
        // La a6000 exige startRecMode antes del liveview; en otros modelos
        // puede no existir o estar ya activo — ambos casos son ignorables.
        do {
            try call("startRecMode", endpoint: endpoint)
        } catch is CameraError {
        }
        guard let url = try call("startLiveview", endpoint: endpoint).first as? String else {
            throw CameraError("startLiveview: no URL in response")
        }
        return url
    }
}
