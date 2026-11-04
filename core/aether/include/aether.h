#ifndef AETHER_FFI_H
#define AETHER_FFI_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef void (*AetherEventCallback)(const char *json);
void aether_set_event_callback(AetherEventCallback callback);
int32_t aether_start_json(const char *json);
/* iOS only: use aether_packet_* queues; no Unix TUN descriptor is accepted. */
int32_t aether_start_json_with_packet_channel(const char *json);
/* Android-only descriptor bridge; never pass an iOS NEPacketTunnelFlow here. */
int32_t aether_start_json_with_tun(const char *json, int32_t tun_fd);
int32_t aether_stop(void);
int32_t aether_is_running(void);
int32_t aether_is_ready(void);
/* iOS queue primitive: family=4 IPv4, family=6 IPv6.
 * Push: 1 queued, 0 full/backpressure, -1 invalid.
 * Pop: byte count, 0 empty, -required_size if buffer is too small, -1 invalid.
 * Bounded queues must be reset between provider sessions.
 * These queues are not yet connected to Aether's live transport loop.
 */
int32_t aether_packet_channel_reset(void);
int32_t aether_packet_push_inbound(const uint8_t *packet, size_t length, uint8_t family);
intptr_t aether_packet_pop_inbound(uint8_t *output, size_t capacity, uint8_t *family_out);
int32_t aether_packet_push_outbound(const uint8_t *packet, size_t length, uint8_t family);
intptr_t aether_packet_pop_outbound(uint8_t *output, size_t capacity, uint8_t *family_out);
const char *aether_last_error(void);
const char *aether_last_result(void);
const char *aether_last_log(void);
const char *aether_version(void);
#ifdef __cplusplus
}
#endif
#endif /* AETHER_FFI_H */
