import Foundation
import Network

final class ConnectivityService {
    nonisolated(unsafe) static let shared = ConnectivityService()

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "spaces.connectivity.monitor")
    private(set) var isConnected = true
    var onConnectivityChanged: ((Bool) -> Void)?

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let connected = path.status == .satisfied
            guard let self, self.isConnected != connected else { return }
            self.isConnected = connected
            DispatchQueue.main.async {
                self.onConnectivityChanged?(connected)
            }
        }
        monitor.start(queue: queue)
    }
}
