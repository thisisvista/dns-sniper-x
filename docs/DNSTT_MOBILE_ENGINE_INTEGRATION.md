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
- [x] Inspect the generated Java API and add a Kotlin DNSTT lifecycle/E2E adapter.
- [ ] Verify the gomobile-compatible socket-protection interface in CI, then wire it to Android VPN protection where applicable.
- [x] Add a local fake-SOCKS5 HTTP probe test for protocol framing and response parsing.
- [ ] Add a real controlled DNSTT server + upstream integration test.
- [ ] Connect the engine to the scanner workflow and persist per-network resolver evidence.

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

The mobile binding's local TCP listener is a raw stream forwarder, not itself a SOCKS5 server. SniperX must act as the SOCKS5 client over that local TCP stream, and the DNSTT server's `UPSTREAMADDR` must point at a SOCKS5-capable server. A correct SniperX full verification should therefore:

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

These findings mean the AAR is a successful build artifact, **not yet an integration-ready SniperX engine**. The build script now applies a guarded source transformation before binding: it replaces the function callback with a gomobile-compatible `SocketProtector` interface and removes the unused TUN-FD setter/field. CI must verify that the generated Java API exposes `setSocketProtector` and the callback's `protect(long)` method. Even after it builds, the Kotlin adapter must wire this callback to `VpnService.protect(fd)` when running alongside an Android VPN service, and that path needs on-device testing. A local fake-SOCKS5 HTTP probe test has been added; a real controlled DNSTT server test remains outstanding.


## Evidence and TLS hardening follow-up

The Kotlin adapter must not report `HANDSHAKE_PASS` merely because `DnsttClient.isRunning()` becomes true. That property confirms the client lifecycle state, not completion of the Noise handshake. The adapter now returns `NOT_TESTED` for handshake-only requests until the binding exposes a trustworthy handshake-completion signal. Full verification remains the path that can prove a real application request traversed the tunnel.

HTTPS verification now enables the TLS endpoint-identification algorithm (`HTTPS`) on the layered SSLSocket before the handshake. This ensures the certificate identity is checked against the requested URL host rather than accepting any certificate presented by the upstream.

A unit test protects the no-false-positive handshake behavior. These changes do not implement a VPN service or socket-protection wiring; the app manifest still has no VpnService declaration, and a real controlled DNSTT server test remains a release blocker.

## Current SniperX wiring boundary

`DnsttMobileE2eEngine` now implements the engine contract and performs the DNSTT start/stop lifecycle plus SOCKS5 CONNECT and HTTP status verification. Its local SOCKS5 protocol path has a deterministic loopback test. However, the current application tree does not yet inject this engine into the resolver scan workflow or persist its result against the active network/provider evidence. The engine is therefore an implemented component under test, not yet an end-user feature exposed by the UI. Do not claim that the app can currently scan candidates and automatically promote DNSTT E2E winners.


## Listener allocation race hardening

The pinned Go binding is adapted during the reproducible build to expose
`GetListenPort()`, which returns the port of the listener that the Go client
actually bound. Kotlin now asks Go to bind `127.0.0.1:0` and reads that port
after `Start()` completes. This removes the previous reserve-then-close
port-allocation race, where another local process could claim the port before
the Go listener binds it.

The AAR build now fails unless the generated Java API includes
`getListenPort()`, in addition to the socket-protection callback methods.
This is a build-time API contract check; it does not replace runtime tunnel
testing.
