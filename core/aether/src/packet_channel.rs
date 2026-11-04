//! Bounded packet queues for the future iOS NEPacketTunnelFlow adapter.
//!
//! This is only the FFI-safe queue primitive. It is deliberately not wired into
//! the live Aether transport yet; the current Android fd bridge remains unchanged.
use std::collections::VecDeque;
use std::ffi::c_int;
use std::ptr;
use std::slice;
use std::sync::{LazyLock, Mutex};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use tokio::time::{sleep, Duration};

const MAX_PACKET_BYTES: usize = 65_535;
const MAX_QUEUED_PACKETS: usize = 256;
const MAX_QUEUED_BYTES: usize = 2 * 1024 * 1024;

/// Address family is represented portably across Apple and Linux: 4 or 6.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(C)]
pub enum PacketFamily {
    Ipv4 = 4,
    Ipv6 = 6,
}

#[derive(Clone, Debug)]
struct Packet {
    family: PacketFamily,
    bytes: Vec<u8>,
}

#[derive(Default)]
struct DirectionQueue {
    packets: VecDeque<Packet>,
    bytes: usize,
}

fn family_matches(bytes: &[u8], family: PacketFamily) -> bool {
    matches!((bytes.first().map(|byte| byte >> 4), family),
        (Some(4), PacketFamily::Ipv4) | (Some(6), PacketFamily::Ipv6))
}

impl DirectionQueue {
    fn push(&mut self, family: PacketFamily, bytes: &[u8]) -> Result<(), ()> {
        if bytes.is_empty()
            || bytes.len() > MAX_PACKET_BYTES
            || self.packets.len() >= MAX_QUEUED_PACKETS
            || self.bytes.saturating_add(bytes.len()) > MAX_QUEUED_BYTES
        {
            return Err(());
        }
        self.bytes += bytes.len();
        self.packets.push_back(Packet { family, bytes: bytes.to_vec() });
        Ok(())
    }

    fn pop(&mut self, output: &mut [u8]) -> Result<Option<(usize, PacketFamily)>, usize> {
        let Some(packet) = self.packets.front() else { return Ok(None) };
        if output.len() < packet.bytes.len() {
            return Err(packet.bytes.len());
        }
        let packet = self.packets.pop_front().expect("front was present");
        self.bytes -= packet.bytes.len();
        output[..packet.bytes.len()].copy_from_slice(&packet.bytes);
        Ok(Some((packet.bytes.len(), packet.family)))
    }

    fn clear(&mut self) {
        self.packets.clear();
        self.bytes = 0;
    }
}

#[derive(Default)]
struct PacketChannel {
    inbound: DirectionQueue,
    outbound: DirectionQueue,
}

static CHANNEL: LazyLock<Mutex<PacketChannel>> =
    LazyLock::new(|| Mutex::new(PacketChannel::default()));

/// Connects the iOS queues to Aether's existing packet channels.
/// `core_tx` carries packets from the device toward the remote transport;
/// `core_rx` carries decrypted packets back toward the device.
pub fn spawn_bridge(
    core_tx: mpsc::Sender<Vec<u8>>,
    mut core_rx: mpsc::Receiver<Vec<u8>>,
) -> JoinHandle<()> {
    tokio::spawn(async move {
        let mut inbound_task = tokio::spawn(async move {
            let mut buffer = vec![0u8; MAX_PACKET_BYTES];
            loop {
                let packet = {
                    let Ok(mut channel) = CHANNEL.lock() else { break };
                    match channel.inbound.pop(&mut buffer) {
                        Ok(Some((length, _family))) => Some(buffer[..length].to_vec()),
                        Ok(None) | Err(_) => None,
                    }
                };
                if let Some(packet) = packet {
                    if core_tx.send(packet).await.is_err() { break; }
                } else {
                    sleep(Duration::from_millis(2)).await;
                }
            }
        });

        let mut outbound_task = tokio::spawn(async move {
            while let Some(packet) = core_rx.recv().await {
                let family = match packet.first().map(|byte| byte >> 4) {
                    Some(4) => PacketFamily::Ipv4,
                    Some(6) => PacketFamily::Ipv6,
                    _ => continue,
                };
                loop {
                    let queued = {
                        let Ok(mut channel) = CHANNEL.lock() else { return };
                        channel.outbound.push(family, &packet).is_ok()
                    };
                    if queued { break; }
                    sleep(Duration::from_millis(2)).await;
                }
            }
        });

        tokio::select! {
            _ = &mut inbound_task => {},
            _ = &mut outbound_task => {},
        }
        inbound_task.abort();
        outbound_task.abort();
    })
}

/// Clears both directions before a new provider session.
#[no_mangle]
pub extern "C" fn aether_packet_channel_reset() -> c_int {
    let Ok(mut channel) = CHANNEL.lock() else { return -1 };
    channel.inbound.clear();
    channel.outbound.clear();
    0
}

/// Enqueues a packet read from NEPacketTunnelFlow (family must be 4 or 6).
/// Returns 1 on success, 0 when full/oversized, and -1 for invalid arguments.
#[no_mangle]
pub unsafe extern "C" fn aether_packet_push_inbound(
    packet: *const u8,
    length: usize,
    family: u8,
) -> c_int {
    if packet.is_null() || length == 0 || length > MAX_PACKET_BYTES {
        return -1;
    }
    let family = match family {
        4 => PacketFamily::Ipv4,
        6 => PacketFamily::Ipv6,
        _ => return -1,
    };
    let bytes = unsafe { slice::from_raw_parts(packet, length) };
    if !family_matches(bytes, family) { return -1; }
    let Ok(mut channel) = CHANNEL.lock() else { return -1 };
    if channel.inbound.push(family, bytes).is_ok() { 1 } else { 0 }
}

/// Dequeues a packet for the native core. Returns byte count, 0 if empty,
/// -1 for invalid arguments, or -required_size if the supplied buffer is small.
#[no_mangle]
pub unsafe extern "C" fn aether_packet_pop_inbound(
    output: *mut u8,
    capacity: usize,
    family_out: *mut u8,
) -> isize {
    if output.is_null() || family_out.is_null() || capacity == 0 { return -1; }
    let output = unsafe { slice::from_raw_parts_mut(output, capacity) };
    let Ok(mut channel) = CHANNEL.lock() else { return -1 };
    match channel.inbound.pop(output) {
        Ok(None) => 0,
        Ok(Some((length, family))) => {
            unsafe { ptr::write(family_out, family as u8) };
            length as isize
        }
        Err(required) => -(required as isize),
    }
}

/// Enqueues a packet emitted by the native core for NEPacketTunnelFlow.
#[no_mangle]
pub unsafe extern "C" fn aether_packet_push_outbound(
    packet: *const u8,
    length: usize,
    family: u8,
) -> c_int {
    if packet.is_null() || length == 0 || length > MAX_PACKET_BYTES { return -1; }
    let family = match family {
        4 => PacketFamily::Ipv4,
        6 => PacketFamily::Ipv6,
        _ => return -1,
    };
    let bytes = unsafe { slice::from_raw_parts(packet, length) };
    if !family_matches(bytes, family) { return -1; }
    let Ok(mut channel) = CHANNEL.lock() else { return -1 };
    if channel.outbound.push(family, bytes).is_ok() { 1 } else { 0 }
}

/// Dequeues a packet to be written to NEPacketTunnelFlow.
#[no_mangle]
pub unsafe extern "C" fn aether_packet_pop_outbound(
    output: *mut u8,
    capacity: usize,
    family_out: *mut u8,
) -> isize {
    if output.is_null() || family_out.is_null() || capacity == 0 { return -1; }
    let output = unsafe { slice::from_raw_parts_mut(output, capacity) };
    let Ok(mut channel) = CHANNEL.lock() else { return -1 };
    match channel.outbound.pop(output) {
        Ok(None) => 0,
        Ok(Some((length, family))) => {
            unsafe { ptr::write(family_out, family as u8) };
            length as isize
        }
        Err(required) => -(required as isize),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_family_metadata_that_disagrees_with_ip_header() {
        assert!(!family_matches(&[0x45, 0, 0, 0], PacketFamily::Ipv6));
        assert!(!family_matches(&[0x60, 0, 0, 0], PacketFamily::Ipv4));
        assert!(family_matches(&[0x45, 0, 0, 0], PacketFamily::Ipv4));
        assert!(family_matches(&[0x60, 0, 0, 0], PacketFamily::Ipv6));
    }

    #[test]
    fn preserves_packet_bytes_and_family_in_both_directions() {
        let mut queue = DirectionQueue::default();
        let packet = [0x45, 0x00, 0x00, 0x14];
        queue.push(PacketFamily::Ipv4, &packet).unwrap();
        let mut output = [0u8; 8];
        let (length, family) = queue.pop(&mut output).unwrap().unwrap();
        assert_eq!(length, packet.len());
        assert_eq!(family, PacketFamily::Ipv4);
        assert_eq!(&output[..length], &packet);
        assert_eq!(queue.pop(&mut output).unwrap(), None);
    }

    #[test]
    fn refuses_to_consume_packet_when_output_buffer_is_small() {
        let mut queue = DirectionQueue::default();
        queue.push(PacketFamily::Ipv6, &[1, 2, 3, 4]).unwrap();
        let mut small = [0u8; 2];
        assert_eq!(queue.pop(&mut small), Err(4));
        let mut enough = [0u8; 4];
        assert_eq!(queue.pop(&mut enough).unwrap(), Some((4, PacketFamily::Ipv6)));
    }

    #[test]
    fn enforces_packet_and_queue_bounds() {
        let mut queue = DirectionQueue::default();
        assert!(queue.push(PacketFamily::Ipv4, &[]).is_err());
        assert!(queue.push(PacketFamily::Ipv4, &vec![0; MAX_PACKET_BYTES + 1]).is_err());
        for _ in 0..MAX_QUEUED_PACKETS {
            queue.push(PacketFamily::Ipv4, &[0x45]).unwrap();
        }
        assert!(queue.push(PacketFamily::Ipv4, &[0x45]).is_err());
    }

    #[test]
    fn enforces_total_queued_byte_budget_independently_of_packet_count() {
        let mut queue = DirectionQueue::default();
        let max_packet = vec![0x45; MAX_PACKET_BYTES];
        for _ in 0..(MAX_QUEUED_BYTES / MAX_PACKET_BYTES) {
            queue.push(PacketFamily::Ipv4, &max_packet).unwrap();
        }
        let remaining = MAX_QUEUED_BYTES % MAX_PACKET_BYTES;
        assert!(remaining > 0);
        queue.push(PacketFamily::Ipv4, &vec![0x45; remaining]).unwrap();
        assert_eq!(queue.bytes, MAX_QUEUED_BYTES);
        assert!(queue.push(PacketFamily::Ipv4, &[0x45]).is_err());
    }

    #[test]
    fn clear_releases_all_queued_packets_and_byte_accounting() {
        let mut queue = DirectionQueue::default();
        queue.push(PacketFamily::Ipv6, &[1, 2, 3, 4]).unwrap();
        queue.clear();
        assert_eq!(queue.bytes, 0);
        assert!(queue.packets.is_empty());
        queue.push(PacketFamily::Ipv4, &[0x45]).unwrap();
        assert_eq!(queue.bytes, 1);
    }
}
