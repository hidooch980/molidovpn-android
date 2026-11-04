# Molido Mesh AI — Android visual system (v3)

## Product direction

MolidoVPN is presented as a modern, intelligent, privacy-first mesh network client.
The visual language should communicate adaptive networking and security without
looking like a generic VPN template or a neon gaming dashboard.

The redesign is native Android Views and reuses the app's existing connection,
network intelligence, diagnostics, server, update and privacy logic. Visual
components must render real state only; never fabricate speed, latency, node
health, AI confidence or protection status.

## Visual foundations

### Dark — Mesh Night
- Canvas: `#080D1B`
- Raised surface: `#111A2D`
- Secondary surface: `#192640`
- Primary text: `#F2F6FF`
- Muted text: `#A3B0C8`
- Teal action: `#43E6CF`
- Connected: `#52E6A6`
- Violet intelligence accent: `#8B82FF`
- Amber attention: `#FFC36B`
- Error: `#FF6B82`

### Light — Mesh Mist
- Canvas: `#F1F5FC`
- Raised surface: `#FFFFFF`
- Secondary surface: `#E8EEF8`
- Primary text: `#111B31`
- Muted text: `#596982`
- Teal action: `#087F79`
- Connected: `#087D65`
- Violet intelligence accent: `#6559D9`

Both palettes share the same semantic roles. Text accents must pass WCAG AA
contrast on the surface on which they are used. Vivid accents may be used for
shapes and indicators while darker siblings are used for text.

## Background and depth

- A subtle adaptive mesh backdrop may use low-opacity network lines, nodes and
  ambient teal/violet radial light.
- The mesh is decorative only. It must not imply a live topology or display
  synthetic network measurements.
- Animate slowly, pause when detached/backgrounded, and avoid continuous network
  or CPU work.
- Cards remain readable and visually distinct from the background. Avoid heavy
  glass blur, high-frequency particles, excessive glow and nested-card overload.

## Home screen

- Keep the main connect action visually dominant and immediately reachable.
- Show truthful connection state, selected route/protocol and location.
- Show ping, download, upload, traffic and session duration only from live sources.
- Surface Smart Connect's actual probing progress, winner, reason and backup when
  available. Clearly distinguish a recommendation from an established tunnel.
- Keep advanced protocol and DNS controls discoverable without overwhelming the
  primary connect flow.
- The top brand identifies Molido Mesh AI; the connection status remains visible.

## Smart network experience

- Explain why a route or transport was selected using measured quality and
  device/network context when those signals exist.
- Display network quality dimensions such as RTT, jitter, packet loss, throughput
  and handover state only when measured and available.
- Never claim that AI has optimized or protected a connection unless the relevant
  engine reports a completed action.
- Keep failover, diagnosis and recovery states explicit and understandable.

## Motion and performance

- Motion should be calm, purposeful and generally under 300ms for interactions.
- Background ambient animation may be slower, but must stop when the view detaches.
- Respect Android reduced-motion/accessibility expectations and preserve touch
  targets, TalkBack labels and readable contrast.
- Do not add heavyweight UI dependencies solely for decoration.

## Localization and accessibility

- Support Persian, English, Arabic and Turkish layouts.
- Use locale-aware direction: Persian and Arabic are RTL; English and Turkish are LTR.
- Avoid letter spacing on joined scripts. Do not truncate critical security or
  connection state text.
- Colour never carries state alone; pair it with text or an accessible description.

## Safety and compatibility guardrails

- Do not remove or bypass existing VPN permission, kill switch, DNS, leak
  protection, profile import, signed update, signer lineage or emergency controls.
- Do not alter package identity, signing configuration, keystore secrets or
  update trust rules as part of a visual redesign.
- Preserve existing public navigation and connection behaviour unless a separate
  tested migration explicitly changes it.
- All new visual work must pass Android compilation and unit tests before merge.
