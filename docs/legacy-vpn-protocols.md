# L2TP and PPTP scope

Requested alongside OpenVPN and AnyConnect on 2026-10-20. Neither protocol is
implemented or enabled in the app.

L2TP is considered only together with IPsec. Plain L2TP is not a confidentiality
mechanism. Implementation needs an Android-compatible IPsec/IKE and PPP/L2TP
stack, protected upstream sockets, certificate/PSK handling, NAT traversal and
tests on current phone networks. IPsec/PPP parameters cannot be inferred from an
OpenVPN profile or an AnyConnect address. No free working server has been verified.

PPTP is recorded as a legacy compatibility request. It is not recommended and is
excluded from automatic selection. Any future manual compatibility mode must
explain its security limitations and use explicitly authorized server credentials.
No new PPTP dependency or network traffic is introduced by this document.

References:
- https://www.rfc-editor.org/info/rfc3193/
- https://learn.microsoft.com/en-us/windows-server/remote/remote-access/configure-vpn-protocols
