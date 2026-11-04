import Foundation
import NetworkExtension
import AetherFFI
import Darwin

/// Owns the blocking Aether lifecycle and the NEPacketTunnelFlow packet pumps.
final class MolidoAetherPacketAdapter {
    enum AdapterError: LocalizedError {
        case invalidStartOptions
        case nativeStartFailed(String)
        case readinessTimeout
        case malformedPacket
        case inboundBufferLimit
        case nativeQueueFailure(Int)
        case packetFlowWriteFailed

        var errorDescription: String? {
            switch self {
            case .invalidStartOptions: return "Aether start options are empty or contain a NUL byte."
            case .nativeStartFailed(let message): return "Aether failed: \(message)"
            case .readinessTimeout: return "Aether did not become ready before the startup deadline."
            case .malformedPacket: return "The packet flow returned an invalid IPv4/IPv6 packet."
            case .inboundBufferLimit: return "The bounded packet handoff buffer is full; tunnel stopped without dropping traffic."
            case .nativeQueueFailure(let code): return "Aether packet queue failed (\(code))."
            case .packetFlowWriteFailed: return "NetworkExtension rejected an outbound packet batch."
            }
        }
    }

    private struct PendingPacket {
        let data: Data
        let family: UInt8
    }

    private let controlQueue = DispatchQueue(label: "com.molido.aether.control")
    private let workerQueue = DispatchQueue(label: "com.molido.aether.worker", qos: .userInitiated)
    private var readinessTimer: DispatchSourceTimer?
    private var inboundRetryTimer: DispatchSourceTimer?
    private var outboundTimer: DispatchSourceTimer?
    private var healthTimer: DispatchSourceTimer?
    private weak var packetFlow: NEPacketTunnelFlow?
    private var readyHandler: ((Result<Void, Error>) -> Void)?
    private var failureHandler: ((Error) -> Void)?
    private var stopHandlers: [() -> Void] = []
    private var startupDeadline: DispatchTime = .now() + 20
    private var started = false
    private var stopping = false
    private var pumpsActive = false
    private var pendingInbound: [PendingPacket] = []
    private var pendingInboundBytes = 0
    private let maxPendingPackets = 256
    private let maxPendingBytes = 2 * 1024 * 1024

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
            guard !engineOptionsJSON.isEmpty, !engineOptionsJSON.contains("\0") else {
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
                let result = engineOptionsJSON.withCString { aether_start_json_with_packet_channel($0) }
                let nativeMessage: String = {
                    guard let pointer = aether_last_error() else { return "Native start returned \(result)." }
                    let value = String(cString: pointer)
                    return value.isEmpty ? "Native start returned \(result)." : value
                }()
                self.controlQueue.async {
                    self.cancelReadinessTimer()
                    // A native exit at any point after readiness is a tunnel failure,
                    // not a successful disconnect hidden behind an active packet pump.
                    if !self.stopping {
                        let error = AdapterError.nativeStartFailed(nativeMessage)
                        if self.readyHandler != nil {
                            self.finishStartup(.failure(error))
                            self.stop()
                        } else {
                            self.fail(error)
                        }
                    }
                    self.started = false
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
                    self.stop()
                }
            }
            self.readinessTimer = timer
            timer.resume()
        }
    }

    /// Activate only after the provider has installed validated full-tunnel routes and DNS.
    func activatePacketFlow(
        _ flow: NEPacketTunnelFlow,
        activated: @escaping (Result<Void, Error>) -> Void,
        failed: @escaping (Error) -> Void
    ) {
        controlQueue.async {
            guard self.started, !self.stopping, !self.pumpsActive,
                  aether_is_running() == 1, aether_is_ready() == 1 else {
                let error = AdapterError.nativeStartFailed(
                    "Packet flow activation attempted before engine readiness."
                )
                activated(.failure(error))
                return
            }
            self.packetFlow = flow
            self.failureHandler = failed
            self.pumpsActive = true
            self.readNextPacketBatch()
            self.startOutboundPump()
            self.startHealthMonitor()
            activated(.success(()))
        }
    }

    /// Waits for the serial native worker to leave the blocking FFI before completing.
    func stop(completion: (() -> Void)? = nil) {
        controlQueue.async {
            if let completion { self.stopHandlers.append(completion) }
            guard !self.stopping else { return }
            self.stopping = true
            self.cancelReadinessTimer()
            self.inboundRetryTimer?.cancel()
            self.inboundRetryTimer = nil
            self.outboundTimer?.cancel()
            self.outboundTimer = nil
            self.healthTimer?.cancel()
            self.healthTimer = nil
            self.pumpsActive = false
            self.packetFlow = nil
            self.readyHandler = nil
            self.failureHandler = nil
            self.pendingInbound.removeAll(keepingCapacity: false)
            self.pendingInboundBytes = 0
            _ = aether_stop()

            self.workerQueue.async {
                self.controlQueue.async {
                    _ = aether_packet_channel_reset()
                    self.started = false
                    let handlers = self.stopHandlers
                    self.stopHandlers.removeAll()
                    handlers.forEach { $0() }
                }
            }
        }
    }

    private func finishStartup(_ result: Result<Void, Error>) {
        let handler = readyHandler
        readyHandler = nil
        handler?(result)
    }

    private func fail(_ error: Error) {
        guard !stopping else { return }
        if readyHandler != nil {
            finishStartup(.failure(error))
        } else {
            failureHandler?(error)
        }
        stop()
    }

    private func cancelReadinessTimer() {
        readinessTimer?.setEventHandler {}
        readinessTimer?.cancel()
        readinessTimer = nil
    }

    private func readNextPacketBatch() {
        guard pumpsActive, !stopping, pendingInbound.isEmpty, let flow = packetFlow else { return }
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
                    guard expectedProtocol != 0, proto.int32Value == Int32(expectedProtocol) else {
                        self.fail(AdapterError.malformedPacket)
                        return
                    }
                    guard self.pendingInbound.count < self.maxPendingPackets,
                          self.pendingInboundBytes + packet.count <= self.maxPendingBytes else {
                        self.fail(AdapterError.inboundBufferLimit)
                        return
                    }
                    self.pendingInbound.append(PendingPacket(data: packet, family: family))
                    self.pendingInboundBytes += packet.count
                }
                self.pumpInboundQueue()
            }
        }
    }

    /// A full native queue is backpressure, not packet loss: retain the consumed
    /// NetworkExtension batch and retry until Aether drains its inbound queue.
    private func pumpInboundQueue() {
        guard pumpsActive, !stopping else { return }
        while let pending = pendingInbound.first {
            let result = pending.data.withUnsafeBytes { bytes -> Int32 in
                guard let base = bytes.bindMemory(to: UInt8.self).baseAddress else { return -1 }
                return aether_packet_push_inbound(base, pending.data.count, pending.family)
            }
            if result == 0 {
                scheduleInboundRetry()
                return
            }
            if result < 0 {
                fail(AdapterError.nativeQueueFailure(Int(result)))
                return
            }
            pendingInbound.removeFirst()
            pendingInboundBytes -= pending.data.count
        }
        inboundRetryTimer?.cancel()
        inboundRetryTimer = nil
        readNextPacketBatch()
    }

    private func scheduleInboundRetry() {
        guard inboundRetryTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: controlQueue)
        timer.schedule(deadline: .now() + .milliseconds(2), repeating: .milliseconds(2))
        timer.setEventHandler { [weak self] in self?.pumpInboundQueue() }
        inboundRetryTimer = timer
        timer.resume()
    }

    private func startOutboundPump() {
        let timer = DispatchSource.makeTimerSource(queue: controlQueue)
        timer.schedule(deadline: .now(), repeating: .milliseconds(2))
        timer.setEventHandler { [weak self] in self?.drainOutboundPackets() }
        outboundTimer = timer
        timer.resume()
    }

    /// A runtime that exits after the initial ready signal must not leave the
    /// system reporting a connected tunnel with no packet transport.
    private func startHealthMonitor() {
        let timer = DispatchSource.makeTimerSource(queue: controlQueue)
        timer.schedule(deadline: .now() + .milliseconds(250), repeating: .milliseconds(250))
        timer.setEventHandler { [weak self] in
            guard let self, self.pumpsActive, !self.stopping else { return }
            guard aether_is_running() == 1, aether_is_ready() == 1 else {
                self.fail(AdapterError.nativeStartFailed(
                    "Aether stopped or lost readiness while the packet tunnel was active."
                ))
                return
            }
        }
        healthTimer = timer
        timer.resume()
    }

    private func drainOutboundPackets() {
        guard pumpsActive, !stopping, let flow = packetFlow else { return }
        var packets: [Data] = []
        var protocols: [NSNumber] = []
        packets.reserveCapacity(32)
        protocols.reserveCapacity(32)
        // Reuse one maximum-sized buffer for the whole batch; allocating 32 x
        // 65 KB on every 2 ms tick caused avoidable memory churn on iPhones.
        var buffer = [UInt8](repeating: 0, count: 65_535)

        for _ in 0..<32 {
            var family: UInt8 = 0
            let length = buffer.withUnsafeMutableBufferPointer { output in
                aether_packet_pop_outbound(output.baseAddress, output.count, &family)
            }
            if length == 0 { break }
            if length < 0 {
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
