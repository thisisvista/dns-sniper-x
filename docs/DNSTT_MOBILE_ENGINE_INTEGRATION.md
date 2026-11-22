# DNSTT mobile engine: source and build provenance

## Selected source

- Project: [dnstt_xyz_app](https://github.com/dnstt-xyz/dnstt_xyz_app)
- Pinned source commit: `8155cde0353433646aceacca1414913fc9a304a7`
- Go module path: `www.bamsoftware.com/git/dnstt.git`
- Mobile binding: `go_src/mobile/mobile.go`
- Source license file: `go_src/COPYING` (CC0 1.0 Universal)
- Go toolchain requirement in the pinned `go.mod`: Go 1.24
- gomobile version: `v0.0.0-20260112195712-5b9ecdfb8721`

This is an independently maintained DNSTT mobile binding and source snapshot. It is **not** the missing `anonvector/dnstt-mobile` submodule and is **not** asserted to be byte-for-byte or behaviorally identical to SlipNet v2.5.6. The original SlipNet submodule pointer remains recorded in `SLIPNET_V2_5_6_APK_BINARY_AUDIT.md`.

## Build

Run:

```bash
scripts/build-dnstt-android-aar.sh
```

The script downloads the immutable source archive, verifies Go module checksums, installs the pinned gomobile tool, and builds Android ARM64, ARMv7 and x86_64 bindings for API 26+. The generated AAR is placed under `app/build/dnstt/` and is intentionally not committed as an opaque binary.

## Integration status

- [x] Select and pin a retrievable source snapshot.
- [x] Add a reproducible Android AAR build script.
- [ ] Audit transitive dependency licenses for redistribution.
- [ ] Inspect generated Java API and write the Kotlin lifecycle adapter.
- [ ] Implement isolated resolver socket routing for Android VPN coexistence.
- [ ] Add controlled-server handshake and real application-traffic tests.
- [ ] Connect verified results to the resolver/network evidence store.

Do not mark an E2E result as successful until the tunnel has carried an application-level request through a controlled DNSTT server. A successful AAR build alone proves only that the native binding compiles.

## Protocol and profile constraints discovered during API review

The pinned mobile binding currently opens a plain UDP socket to the resolver address (default port 53). It does not expose DoH or DoT transport modes. It starts a local TCP forwarder; each accepted local TCP connection opens a multiplexed DNSTT stream, and the DNSTT server forwards that stream to the server-side `UPSTREAMADDR` configured by the operator. The client cannot choose an arbitrary remote destination per stream in this binding (`DialTunnel(address)` currently ignores its address argument).

Consequences for SniperX:

- First implementation scope is UDP/53 DNSTT only. DoH/DoT support is a separate transport-engine task, not implied by this AAR.
- A real full-pass test needs a controlled DNSTT server whose upstream endpoint returns a known HTTP response. A generic public URL cannot simply be requested through this fixed-forwarding tunnel.
- The current `TunnelProfile.serverHost/serverPort` fields do not fully describe this server-side forwarding arrangement. Before wiring the adapter, the profile schema must explicitly distinguish the tunnel zone/domain, resolver endpoint, and verification target/expected response. Avoid silently reinterpreting the existing port field.
- Resolver sockets must be protected from any future Android `VpnService` routing loop; the current SniperX manifest does not yet declare or implement a VPN service.

The source repository's top-level project and `go_src/COPYING` both declare CC0 1.0 for their respective project/source. This does not clear the licenses of Go module dependencies. The transitive dependency license audit remains a release gate before redistributing the generated AAR.

## Expected end-to-end verification path

The mobile binding's local TCP listener is a raw stream forwarder, not itself a SOCKS5 server. The upstream Android app implements SOCKS5 negotiation over that local TCP connection; its DNSTT server-side `UPSTREAMADDR` must point at a SOCKS5-capable endpoint. A correct SniperX full verification should therefore:

1. Start the Go DNSTT client against the candidate resolver IP and configured tunnel domain/key.
2. Connect to the local TCP listener.
3. Perform the SOCKS5 greeting and CONNECT request for the verification URL's host and port.
4. Send an HTTP request through the established SOCKS5 stream.
5. Require a valid HTTP response (2xx/3xx plus expected body or challenge token), not merely a TCP accept or SOCKS5 greeting.

This avoids falsely counting the local listener or Noise handshake as successful internet access. The verification endpoint and expected response should be controlled/configurable so the test does not rely solely on one third-party URL.

## Generated AAR API inspection (CI artifact 14749974720)

The first CI-produced AAR was downloaded and inspected with `javap`. It contains `mobile.Mobile` and `mobile.DnsttClient` plus native libraries for ARM64, ARMv7 and x86_64. The Java-visible API is:

- `Mobile.newClient(dnsServer, tunnelDomain, pubKeyHex, listenAddr)`
- `DnsttClient.start()`, `stop()`, `isRunning()`
- `DnsttClient.setTunFd(long)`, `setShareProxy(boolean)`, `isShareProxyEnabled()`

Important API gaps found:

1. The Go source defines `SetProtectSocket(ProtectSocketFunc)`, but gomobile does not expose that function-callback method in the generated Java API. The current AAR therefore cannot safely protect the resolver UDP socket from Android VPN routing.
2. `SetTunFd` is exported but the pinned Go source only stores the descriptor; the inspected implementation does not route packets through it.
3. The exported listener is a raw TCP forwarder, not a SOCKS5 proxy (despite an outdated source comment). Each TCP connection becomes a tunnel stream forwarded to the server's single configured upstream endpoint.
4. The generated API does not expose `DialTunnel` because its return type is Go `net.Conn`.

These findings mean the AAR is a successful build artifact, **not yet an integration-ready SniperX engine**. The next code change must add a gomobile-compatible socket-protection interface or another safe socket-protection mechanism, clarify/remove the unused TUN-FD API, and expose a narrow testable local-forwarding contract. Until then, do not run it inside a VPN path or mark any resolver E2E-ready.
