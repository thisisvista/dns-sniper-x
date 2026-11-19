# Tunnel Client Feasibility Assessment

Status: research completed; no tunnel implementation is included in this change.

## Scope

DNS SniperX is currently an Android DNS resolver discovery and diagnostics app. A successful UDP, TCP, DoH, or DoT check is not evidence that a DNSTT or Slipstream tunnel can be established. Tunnel support requires a compatible server, protocol-specific configuration, a client transport, and (for device-wide routing) an Android VPN/TUN layer.

This assessment uses public upstream documentation as architectural research only. It does not copy SlipNet or another app's UI, branding, or application code.

## Findings

### DNSTT

**Feasibility: technically feasible on Android; integration effort is substantial.**

The public dnstt-xyz Android client documents a Go implementation exposed to Android through gomobile and an AAR. Its tunnel stack uses DNS transport, KCP, Noise_NK encryption, and multiplexed streams, with a local SOCKS5 interface. A DNSTT profile needs a tunnel domain and the server's Noise public key. The selected DNS resolver carries the encoded tunnel traffic.

Likely integration path for DNS SniperX:
1. Evaluate the upstream DNSTT client library and its current license/dependency notices.
2. Build a minimal Android AAR for supported ABIs using a pinned Go/gomobile toolchain.
3. Wrap the client behind a narrow Kotlin interface (start, stop, state, diagnostics).
4. First validate a local SOCKS5-only mode against a controlled server.
5. Only then integrate Android `VpnService` and TUN packet routing, with explicit VPN consent and foreground-service lifecycle handling.

A working DNS resolver alone is insufficient: the server domain, matching public key, and compatible server must also be configured.

### Slipstream

**Feasibility: technically feasible on Android; native packaging and lifecycle are substantial.**

The public dnstt-xyz client documents a Rust Slipstream client built for Android ABIs and launched as a subprocess. Its Android integration passes the selected resolver and tunnel domain to the native client, which exposes a local TCP/SOCKS-compatible endpoint. Slipstream uses QUIC over DNS and TLS 1.3; its configuration differs from DNSTT and does not use the DNSTT Noise public key.

Likely integration path:
1. Select and pin an upstream Slipstream implementation and audit its license and transitive dependencies.
2. Build and package native binaries for each supported Android ABI (at minimum arm64-v8a; decide whether to support armeabi-v7a and x86_64).
3. Implement a process supervisor with bounded startup, health checks, graceful stop, stderr redaction, and crash cleanup.
4. Validate the local proxy against a controlled Slipstream server.
5. Integrate with `VpnService` only after proxy-mode tests pass.

The subprocess must be kept outside the VPN route (or protected with the Android VPN socket-protection mechanism) to avoid routing its own DNS transport back into the tunnel.

## Shared Android architecture required

Both protocols need a separate tunnel subsystem rather than being folded into the existing DNS probe:
- A profile model with protocol-specific validation and encrypted-at-rest handling for secrets.
- A tunnel state machine (idle, starting, connected, stopping, failed) and structured, privacy-conscious logs.
- A foreground `VpnService`, explicit user approval, notification, reconnect/stop behavior, and protection against routing loops.
- A local proxy abstraction for initial controlled tests.
- A TUN packet-routing implementation for full-device VPN mode.
- Controlled integration tests using a server and credentials/certificates owned by the project.

The current resolver scanner and its results should remain usable independently of tunnel state.

## Security and release gates

- Never ship placeholder keys, server addresses, or credentials.
- Do not collect or upload tunnel traffic, DNS contents, or profile secrets.
- Do not log private keys, full configuration URIs, or authentication material.
- Verify every native dependency's license and notices separately; the application-level license of a reference repository does not automatically cover its submodules or transitive dependencies.
- Pin compiler versions, source revisions, and checksums for native artifacts.
- Test on real Wi-Fi and mobile networks; emulator/build success is not proof of tunnel functionality.
- Report tunnel success only after an end-to-end request traverses the tunnel and reaches a controlled test destination.

## Recommended implementation order

1. Keep this feasibility result as a design gate; do not claim tunnel compatibility yet.
2. Add protocol/profile and tunnel-state abstractions without starting a VPN.
3. Implement DNSTT SOCKS5 mode first, using a controlled test server.
4. Implement Slipstream SOCKS5 mode with ABI-specific native packaging.
5. Add the shared Android VPN/TUN layer after both local proxy paths are independently testable.
6. Add controlled end-to-end tests and real-device network coverage.

## Research references

- DNSTT client source and Android build guide: https://github.com/dnstt-xyz/dnstt_xyz_app
- DNSTT client architecture/build notes: https://github.com/dnstt-xyz/dnstt_xyz_app/blob/main/CLAUDE.md
- Slipstream Android client architecture example: https://github.com/VortexOne404/slipstream-client-android
- Android VPN API: https://developer.android.com/develop/connectivity/vpn

These references describe feasibility and architecture, not verified compatibility with DNS SniperX. No external test server or credentials were available for end-to-end validation.
