import Foundation
import NetworkExtension
import AetherFFI
import Darwin

/// Owns the blocking Aether lifecycle and the NEPacketTunnelFlow <-> bounded
/// native packet queues. Network settings are intentionally owned by the provider.
final class MolidoAetherPacketAdapter {
    enum AdapterError: LocalizedError {
        case invalidStartOptions
        case nativeStartFailed(String)
        case readinessTimeout
        case malformedPacket
        case nativeQueueBackpressure
        case nativeQueueFailure(Int)
        case packetFlowWriteFailed

        var errorDescription: String? {
            switch self {
            case .invalidStartOptions:
                return "Aether start options are empty or contain a NUL byte."
            case .nativeStartFailed(let message):
                return "Aether failed to start: \(message)"
            case .readinessTimeout:
                return "Aether did not become ready before the startup deadline."
            case .malformedPacket:
                return "The packet flow returned an invalid IPv4/IPv6 packet."
            case .nativeQueueBackpressure:
                return "The bounded Aether packet queue is full."
            case .nativeQueueFailure(let code):
                return "Aether packet queue failed (\(code))."
            case .packetFlowWriteFailed:
                return "NetworkExtension rejected an outbound packet batch."
            }
        }
    }

    private let controlQueue = DispatchQueue(label: "com.molido.aether.control")
    private let workerQueue = DispatchQueue(
        label: "com.molido.aether.worker",
        qos: .userInitiated
    )
    private var readinessTimer: DispatchSourceTimer?
    private var outboundTimer: DispatchSourceTimer?
    private weak var packetFlow: NEPacketTunnelFlow?
    private var readyHandler: ((Result<Void, Error>) -> Void)?
    private var failureHandler: ((Error) -> Void)?
    private var startupDeadline: DispatchTime = .now() + 20
    private var started = false
    private var stopping = false
    private var pumpsActive = false

    /// Starts the blocking C ABI on a dedicated worker. The supplied JSON must
    /// be produced by a protocol-specific, validated config translator; never
    /// put raw credentials in a file or providerConfiguration.
    func start(
        engineOptionsJSON: String,
        ready: @escaping (Result<Void, Error>) -> Void,
        failed: @escaping (Error) -> Void
    ) {
        controlQueue.async {
            guard !self.started, !self.stopping else {
                ready(.failure(AdapterError.nativeStartFailed("Adapter is already active.")))
                return
            }
            guard !engineOptionsJSON.isEmpty,
                  !engineOptionsJSON.contains("\0") else {
                ready(.failure(AdapterError.invalidStartOptions))
                return
            }

            self.started = true
            self.readyHandler = ready
            self.failureHandler = failed
            self.startupDeadline = .now() + 20

            let reset = aether_packet_channel_reset()
            guard reset == 0 else {
                self.finishStartup(.failure(AdapterError.nativeQueueFailure(Int(reset))))
                return
            }

            self.workerQueue.async {
                let result = engineOptionsJSON.withCString {
                    aether_start_json_with_packet_channel($0)
                }
                let nativeMessage: String = {
                    guard let pointer = aether_last_error() else {
                        return "Native start returned \(result)."
                    }
                    let value = String(cString: pointer)
                    return value.isEmpty ? "Native start returned \(result)." : value
                }()

                self.controlQueue.async {
                    self.cancelReadinessTimer()
                    if !self.stopping && !self.pumpsActive {
                        self.finishStartup(
                            .failure(AdapterError.nativeStartFailed(nativeMessage))
                        )
                    }
                    self.started = false
                    // Rust's RunningGuard resets the channel on return. This
                    // explicit reset makes the next provider session deterministic.
                    _ = aether_packet_channel_reset()
                }
            }

            let timer = DispatchSource.makeTimerSource(queue: self.controlQueue)
            timer.schedule(deadline: .now(), repeating: .milliseconds(50))
            timer.setEventHandler { [weak self] in
                guard let self, self.started, !self.stopping else { return }
                if aether_is_ready() == 1 {
                    self.cancelReadinessTimer()
                    self.finishStartup(.success(()))
                } else if DispatchTime.now() >= self.startupDeadline {
                    self.cancelReadinessTimer()
                    self.finishStartup(.failure(AdapterError.readinessTimeout))
                    self.fail(AdapterError.readinessTimeout)
                } else if aether_is_running() == 0 {
                    self.cancelReadinessTimer()
                    self.finishStartup(
                        .failure(AdapterError.nativeStartFailed("Native engine exited before readiness."))
                    )
                }
            }
            self.readinessTimer = timer
            timer.resume()
        }
    }

    /// Call only after validated NEPacketTunnelNetworkSettings have been applied.
    func activatePacketFlow(
        _ flow: NEPacketTunnelFlow,
        failed: @escaping (Error) -> Void
    ) {
        controlQueue.async {
            guard self.started, !self.stopping, !self.pumpsActive,
                  aether_is_ready() == 1 else {
                failed(AdapterError.nativeStartFailed("Packet flow activation attempted before engine readiness."))
                return
            }
            self.packetFlow = flow
            self.failureHandler = failed
            self.pumpsActive = true
            self.readNextPacketBatch()
            self.startOutboundPump()
        }
    }

    func stop() {
        controlQueue.async {
            guard !self.stopping else { return }
            self.stopping = true
            self.cancelReadinessTimer()
            self.outboundTimer?.cancel()
            self.outboundTimer = nil
            self.pumpsActive = false
            self.packetFlow = nil
            self.readyHandler = nil
            self.failureHandler = nil
            _ = aether_stop()
        }
    }

    private func finishStartup(_ result: Result<Void, Error>) {
        let handler = readyHandler
        readyHandler = nil
        handler?(result)
    }

    private func fail(_ error: Error) {
        guard !stopping else { return }
        failureHandler?(error)
        stop()
    }

    private func cancelReadinessTimer() {
        readinessTimer?.setEventHandler {}
        readinessTimer?.cancel()
        readinessTimer = nil
    }

    private func readNextPacketBatch() {
        guard pumpsActive, !stopping, let flow = packetFlow else { return }
        flow.readPackets { [weak self] packets, protocols in
            guard let self else { return }
            self.controlQueue.async {
                guard self.pumpsActive, !self.stopping else { return }
                guard packets.count == protocols.count else {
                    self.fail(AdapterError.malformedPacket)
                    return
                }

                for (packet, proto) in zip(packets, protocols) {
                    guard let first = packet.first else {
                        self.fail(AdapterError.malformedPacket)
                        return
                    }
                    let family = first >> 4
                    let expectedProtocol = family == 4 ? AF_INET : (family == 6 ? AF_INET6 : 0)
                    guard expectedProtocol != 0,
                          proto.int32Value == Int32(expectedProtocol) else {
                        self.fail(AdapterError.malformedPacket)
                        return
                    }

                    let queued = packet.withUnsafeBytes { bytes -> Int32 in
                        guard let base = bytes.bindMemory(to: UInt8.self).baseAddress else {
                            return -1
                        }
                        return aether_packet_push_inbound(
                            base,
                            packet.count,
                            family
                        )
                    }
                    if queued == 0 {
                        self.fail(AdapterError.nativeQueueBackpressure)
                        return
                    }
                    if queued < 0 {
                        self.fail(AdapterError.nativeQueueFailure(Int(queued)))
                        return
                    }
                }
                self.readNextPacketBatch()
            }
        }
    }

    private func startOutboundPump() {
        let timer = DispatchSource.makeTimerSource(queue: controlQueue)
        timer.schedule(deadline: .now(), repeating: .milliseconds(2))
        timer.setEventHandler { [weak self] in
            self?.drainOutboundPackets()
        }
        outboundTimer = timer
        timer.resume()
    }

    private func drainOutboundPackets() {
        guard pumpsActive, !stopping, let flow = packetFlow else { return }
        var packets: [Data] = []
        var protocols: [NSNumber] = []
        packets.reserveCapacity(32)
        protocols.reserveCapacity(32)

        for _ in 0..<32 {
            var buffer = [UInt8](repeating: 0, count: 65_535)
            var family: UInt8 = 0
            let length = buffer.withUnsafeMutableBufferPointer { output in
                aether_packet_pop_outbound(
                    output.baseAddress,
                    output.count,
                    &family
                )
            }

            if length == 0 { break }
            if length < 0 {
                // A packet larger than the maximum buffer or an invalid queue
                // result is fatal; never silently drop native outbound traffic.
                fail(AdapterError.nativeQueueFailure(Int(length)))
                return
            }
            let count = Int(length)
            guard count <= buffer.count,
                  (family == 4 && buffer[0] >> 4 == 4) ||
                  (family == 6 && buffer[0] >> 4 == 6) else {
                fail(AdapterError.malformedPacket)
                return
            }
            packets.append(Data(buffer[0..<count]))
            protocols.append(NSNumber(value: family == 4 ? AF_INET : AF_INET6))
        }

        if !packets.isEmpty && !flow.writePackets(packets, withProtocols: protocols) {
            fail(AdapterError.packetFlowWriteFailed)
        }
    }
}
