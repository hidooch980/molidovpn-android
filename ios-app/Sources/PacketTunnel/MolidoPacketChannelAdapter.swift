import Foundation
import NetworkExtension
import Darwin
import AetherFFI

enum MolidoPacketAdapterError: LocalizedError {
    case queueResetFailed
    case invalidProtocolMetadata
    case inboundQueueSaturated
    case nativeQueueFailure(Int)
    case packetWriteRejected

    var errorDescription: String? {
        switch self {
        case .queueResetFailed:
            return "Aether packet queues could not be reset."
        case .invalidProtocolMetadata:
            return "Packet family metadata does not match the IP header."
        case .inboundQueueSaturated:
            return "Aether inbound packet queue is saturated; stopping safely."
        case .nativeQueueFailure(let code):
            return "Aether packet queue failed with code \(code)."
        case .packetWriteRejected:
            return "Network Extension rejected an outbound packet."
        }
    }
}

/// Bridges NEPacketTunnelFlow to Aether's bounded C packet queues.
/// Start this only after the native engine reports ready; stop it before
/// stopping/resetting the native engine. It never installs routes itself.
final class MolidoPacketChannelAdapter {
    private let packetFlow: NEPacketTunnelFlow
    private let worker = DispatchQueue(
        label: "com.molido.meshai.packet-channel",
        qos: .userInitiated
    )
    private let lock = NSLock()
    private var isActive = false
    private var outboundTimer: DispatchSourceTimer?
    private var fatalErrorHandler: ((Error) -> Void)?

    private let maximumPacketSize = 65_535

    init(packetFlow: NEPacketTunnelFlow) {
        self.packetFlow = packetFlow
    }

    func start(onFatalError: @escaping (Error) -> Void) throws {
        lock.lock()
        guard !isActive else {
            lock.unlock()
            return
        }
        lock.unlock()

        guard aether_packet_channel_reset() == 0 else {
            throw MolidoPacketAdapterError.queueResetFailed
        }

        lock.lock()
        isActive = true
        fatalErrorHandler = onFatalError
        lock.unlock()

        let timer = DispatchSource.makeTimerSource(queue: worker)
        timer.schedule(
            deadline: .now(),
            repeating: .milliseconds(2),
            leeway: .milliseconds(1)
        )
        timer.setEventHandler { [weak self] in
            self?.drainOutboundPackets()
        }
        outboundTimer = timer
        timer.resume()

        readInboundPackets()
    }

    func stop(resetNativeQueues: Bool = false) {
        lock.lock()
        isActive = false
        fatalErrorHandler = nil
        let timer = outboundTimer
        outboundTimer = nil
        lock.unlock()

        timer?.setEventHandler {}
        timer?.cancel()

        if resetNativeQueues {
            _ = aether_packet_channel_reset()
        }
    }

    private func readInboundPackets() {
        guard active else { return }
        packetFlow.readPackets { [weak self] packets, protocols in
            guard let self, self.active else { return }
            guard packets.count == protocols.count else {
                self.fail(MolidoPacketAdapterError.invalidProtocolMetadata)
                return
            }

            for (packet, proto) in zip(packets, protocols) {
                guard let family = Self.addressFamily(packet),
                      Self.protocolNumber(for: family) == proto.intValue else {
                    self.fail(MolidoPacketAdapterError.invalidProtocolMetadata)
                    return
                }

                let result = packet.withUnsafeBytes { rawBuffer -> Int32 in
                    guard let baseAddress = rawBuffer.bindMemory(to: UInt8.self).baseAddress else {
                        return -1
                    }
                    return aether_packet_push_inbound(
                        baseAddress,
                        rawBuffer.count,
                        family
                    )
                }

                if result == 0 {
                    self.fail(MolidoPacketAdapterError.inboundQueueSaturated)
                    return
                }
                if result < 0 {
                    self.fail(MolidoPacketAdapterError.nativeQueueFailure(Int(result)))
                    return
                }
            }

            self.readInboundPackets()
        }
    }

    private func drainOutboundPackets() {
        guard active else { return }

        var packets: [Data] = []
        var protocols: [NSNumber] = []

        for _ in 0..<128 {
            var bytes = [UInt8](repeating: 0, count: maximumPacketSize)
            var family: UInt8 = 0
            let count = bytes.withUnsafeMutableBufferPointer { buffer -> Int in
                guard let baseAddress = buffer.baseAddress else { return -1 }
                return Int(aether_packet_pop_outbound(
                    baseAddress,
                    buffer.count,
                    &family
                ))
            }

            if count == 0 { break }
            if count < 0 {
                if count == -1 {
                    fail(MolidoPacketAdapterError.nativeQueueFailure(count))
                }
                // A too-small buffer is not expected: native queue maximum is
                // the same as this buffer. Do not spin on a malformed queue.
                break
            }
            guard count <= bytes.count,
                  (family == 4 || family == 6),
                  Self.addressFamily(Data(bytes.prefix(count))) == family else {
                fail(MolidoPacketAdapterError.invalidProtocolMetadata)
                return
            }

            bytes.removeSubrange(count..<bytes.count)
            packets.append(Data(bytes))
            protocols.append(NSNumber(value: Self.protocolNumber(for: family)))
        }

        guard !packets.isEmpty else { return }
        guard packetFlow.writePackets(packets, withProtocols: protocols) else {
            fail(MolidoPacketAdapterError.packetWriteRejected)
            return
        }
    }

    private var active: Bool {
        lock.lock()
        defer { lock.unlock() }
        return isActive
    }

    private func fail(_ error: Error) {
        lock.lock()
        guard isActive else {
            lock.unlock()
            return
        }
        isActive = false
        let handler = fatalErrorHandler
        fatalErrorHandler = nil
        lock.unlock()

        outboundTimer?.setEventHandler {}
        outboundTimer?.cancel()
        outboundTimer = nil
        handler?(error)
    }

    private static func addressFamily(_ packet: Data) -> UInt8? {
        guard let first = packet.first else { return nil }
        switch first >> 4 {
        case 4: return 4
        case 6: return 6
        default: return nil
        }
    }

    private static func protocolNumber(for family: UInt8) -> Int32 {
        family == 4 ? AF_INET : AF_INET6
    }
}
