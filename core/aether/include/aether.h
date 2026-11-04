#ifndef AETHER_FFI_H
#define AETHER_FFI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*AetherEventCallback)(const char *json);

/* Aether native core lifecycle. JSON is UTF-8 and must remain valid for the call. */
void aether_set_event_callback(AetherEventCallback callback);
int32_t aether_start_json(const char *json);

/* Android-only descriptor bridge. Do not pass an iOS NEPacketTunnelFlow here. */
int32_t aether_start_json_with_tun(const char *json, int32_t tun_fd);
int32_t aether_stop(void);
int32_t aether_is_running(void);
int32_t aether_is_ready(void);

/* Returned strings are owned by Aether; copy immediately before another call. */
const char *aether_last_error(void);
const char *aether_last_result(void);
const char *aether_last_log(void);
const char *aether_version(void);

#ifdef __cplusplus
}
#endif

#endif /* AETHER_FFI_H */
