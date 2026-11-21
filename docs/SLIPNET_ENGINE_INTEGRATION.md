# SlipNet scanner and E2E engine integration

## Scope and preservation policy

This work extends DNS SniperX; it is not a rewrite. Existing resolver collection, provider/ASN discovery, UDP/TCP probes, DoH/DoT diagnostics, TXT import/export, SlipNet-compatible export, Room history, paging, adaptive resource limits, and Keystore-backed secret storage remain in place. No feature is removed as part of this integration.

The Compose UI remains unchanged until the engine and its automated tests are stable. New controls are a later UI phase.

## What the source review confirmed

The upstream SlipNet Android scanner is implemented primarily in:

- `app/src/main/java/app/slipnet/domain/repository/ResolverScannerRepository.kt`
- `app/src/main/java/app/slipnet/data/repository/ResolverScannerRepositoryImpl.kt`
- `app/src/main/java/app/slipnet/tunnel/DnsPoolScanner.kt`

The Android implementation has real tunnel E2E paths for DNSTT, DNSTT+SSH, NoizDNS, NoizDNS+SSH, VayDNS, VayDNS+SSH, Slipstream, and Slipstream+SSH. A full verification test starts the tunnel and verifies an HTTP request through it (or verifies the SSH endpoint for SSH variants). A handshake-only result is a weaker, separately labelled success and must not be presented as full HTTP-verified E2E.

Slipstream uses a native singleton and must be serialized. DNSTT/NoizDNS and VayDNS have isolated client paths with independent local listener ports. These concurrency constraints must be retained.

## Upstream runtime dependencies discovered

This is not a small, standalone Kotlin scanner file. Its implementation relies on:

- the upstream `golibs.aar` (about 12 MB in the reviewed revision);
- the `dnstt-mobile`, `vaydns-mobile`, `dnstt`, `noizdns`, and related Go modules/submodules;
- the Rust Slipstream implementation and its Android native build setup;
- SlipNet's profile model, tunnel bridges, SOCKS/SSH verification helpers, and transport configuration.

Copying only `ResolverScannerRepositoryImpl.kt` would not produce a working E2E engine. Pulling the entire upstream Android app into this app would also import unrelated VPN/UI features and build complexity. The integration should isolate only the scanner and required tunnel runtime behind a SniperX-owned adapter boundary.

## Planned integration sequence

1. Keep SniperX's existing source collectors and resolver database as the candidate source of truth.
2. Keep the existing fast DNS/UDP/TCP checks. Feed only eligible candidates into deeper compatibility and E2E stages.
3. Add secure import/storage of a user's own compatible tunnel profile. Public keys and connection metadata may be stored as profile metadata; passwords/private keys stay in the existing Keystore-backed vault.
4. Integrate the minimum upstream runtime required for the supported tunnel type, with its license notices and source obligations preserved.
5. Add a tunnel engine adapter returning structured phase, tunnel setup time, verification latency, HTTP status, and failure reason.
6. Store separate statuses for DNS, UDP, TCP, tunnel handshake, and full E2E. Never infer E2E success from DNS response, EDNS, or a tunnel-shaped DNS probe.
7. Add unit, instrumentation, and real-device network tests before exposing new UI.

## License and distribution

The reviewed SlipNet revision is licensed under GNU AGPL-3.0. Directly incorporating or modifying its code/runtime into a distributed SniperX app has source-disclosure and license-notice consequences for the combined work. This branch records the dependency and integration plan; no SlipNet implementation source has been copied into SniperX yet. Before merging copied upstream implementation, the app's distribution license and corresponding source publication must be handled explicitly.

## Current test baseline

The Android instrumentation workflow's unit-test step passed, but the UI instrumentation test did not compile because it imported a Compose test extension that is not present in the resolved dependency version. The test now uses the supported `fetchSemanticsNode()` assertion path. The next CI run must verify this correction before engine integration proceeds.
