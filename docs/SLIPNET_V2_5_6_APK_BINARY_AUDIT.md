# SlipNet v2.5.6 APK binary audit

Date: 2026-11-22

## Scope and provenance

Inspected the user-provided file `SlipNet-v2.5.6-full-release-arm64-v8a.apk` as a binary reference. This is an ARM64 release APK, not a source archive.

APK SHA-256:

`f796f0b7e7165c4aefa71feeeb1cd42052053b56768a2474165c1cc4b9fc2ca2`

This audit records observable package contents and exported native symbol names. It does not establish that the binaries can be safely or legally redistributed, nor that their APIs are sufficient to integrate them into DNS SniperX.

## Native components observed

| APK entry | Size (bytes) | SHA-256 |
|---|---:|---|
| `lib/arm64-v8a/libgojni.so` | 18,375,912 | `38fde6051ba8157b18605b6c8ec8bed92722e98070cb99318955f19972961aa2` |
| `lib/arm64-v8a/libslipstream.so` | 5,659,408 | `4976b633d35432094f21b01eafc2c4b4fd0acfa0e92e2b20c1270560d1489e0e` |
| `lib/arm64-v8a/libhev-socks5-tunnel.so` | 319,424 | `ef25de9371c6d0fb978195c130741222cdc162cab589f76d2b1d4212b2bf417c` |
| `lib/arm64-v8a/libhev-tunnel-jni.so` | 7,424 | `3fc54add0d2675c0ac3390f4dd7227fe85af1eb91cc6d5d42c4110e13bad10cd` |

Other native entries observed include `libnaive.so`, `libobfs4proxy.so`, `libtor.so`, and `libslipnet-sockops.so`.

### DNSTT-related exported names

The Go JNI library contains names consistent with a gomobile DNSTT client bridge, including:

- `DnsttClient_Start`, `DnsttClient_Stop`, `DnsttClient_IsRunning`
- `DnsttClient_SetResolverMode`
- `DnsttClient_SetEDNS0Size`, `DnsttClient_SetMaxPayload`
- `DnsttClient_SetAuthoritativeMode`
- `DnsttClient_SetStealthMode`, `DnsttClient_SetNoizMode`
- `DnsttClient_SetSOCKS5Proxy`, `DnsttClient_SetSocksCredentials`
- `DnsttClient_SetUTLSFingerprint`

These are symbol/string observations only. Exact parameter signatures, lifecycle contract, Java package/class names, and compatibility must be established from the matching source and generated binding metadata before use.

### Slipstream-related exported names

The Slipstream library contains JNI names consistent with:

- `SlipstreamBridge_nativeStartSlipstreamClient`
- `SlipstreamBridge_nativeStopSlipstreamClient`
- `SlipstreamBridge_nativeIsClientRunning`
- `SlipstreamBridge_nativeIsQuicReady`
- `SlipstreamBridge_nativeGetLastError`

The library also contains Rust build/source-path strings. This does not prove that this one shared object is a complete, standalone Slipstream distribution; inspect all native and Java-side dependencies in the matching source revision.

### SOCKS/TUN-related exports

The HEV JNI library exposes start/stop/running and statistics methods, plus controls for rejecting QUIC and non-DNS UDP. This suggests a separate SOCKS/TUN integration layer, not the DNS tunnel protocol engine itself.

## Resolver-range assets observed

| APK entry | Non-comment, non-empty lines | SHA-256 |
|---|---:|---|
| `assets/geo/ir.cidr` | 1,185 | `caaed65a1d5f332bd78655b3f605bf016f3edd1fdc1d8141a79e18945ab9c0c1` |
| `assets/geo/ir_dns.cidr` | 3,923 | `b2f11df0bcc11503b7407b025451ab3d637d8d9757759a6910f10fbb3ced2a7f` |
| `assets/geo/ir_dns_lite.cidr` | 92 | `bad3a437c948eb7e045365a3832a4eead9ea9b33ceecf67de05ec21330a22467` |

These line counts are not counts of individual IP addresses. CIDR overlap, source provenance, freshness, and actual resolver reachability have not yet been assessed. Do not merge these ranges into the production collector until deduplication, licensing/provenance, and safe-scan policy have been reviewed.

## Integration decision

1. Do not copy the APK's native libraries into SniperX as an assumed working integration. Their exact source revision, ABI dependencies, licensing notices, Kotlin/Java bridge, and runtime contract must be matched first.
2. Prefer building a minimal, reproducible DNSTT Android binding from pinned source and submodule revisions, with only the APIs SniperX needs.
3. Treat Slipstream as a separate native integration; its Rust/Picoquic/OpenSSL build and ABI matrix must be independently reproducible.
4. Keep the existing `SlipNetE2eEngine` unavailable state until a real client is started and a controlled test server proves handshake and application traffic.
5. Do not alter or replace SniperX's existing collectors, Room database, adaptive scan policy, import/export, or diagnostic tests as part of engine integration.
6. Before distributing any derivative or linked combined work, complete an AGPL-3.0 and dependency-license compliance review and provide the corresponding source/notices as required.

## Next engineering steps

- Obtain and pin the exact SlipNet source revision corresponding to this APK release; the APK's Go build metadata alone is not enough to map every native artifact to a source commit.
- Inspect the upstream Android bridge source and exact submodule gitlinks.
- Confirm the standalone DNSTT binding API, module license, and reproducible gomobile build.
- Build the smallest DNSTT-only AAR for the app's supported ABIs; add checksum and license notices.
- Implement a Kotlin adapter behind `SlipNetE2eEngine` and test it against a controlled DNSTT server.
- Only after DNSTT tests pass, begin Slipstream native packaging and Android VPN/TUN integration.


## Exact upstream source match confirmed

The APK's embedded version-control metadata records source revision:

`caccd9e22fa084264db0bda70c8d694d0f177937`

GitHub's `v2.5.6` release tag resolves to that exact commit. This provides a matching source tree for the uploaded APK, rather than relying on the previously audited, older source revision.

At the matching commit, the following source files were inspected:

- `app/src/main/java/app/slipnet/tunnel/DnsttBridge.kt`
- `app/src/main/java/app/slipnet/tunnel/SlipstreamBridge.kt`
- `app/src/main/java/app/slipnet/tunnel/HevSocks5Tunnel.kt`
- `app/src/main/java/app/slipnet/service/SlipNetVpnService.kt`
- `.gitmodules`
- `gomobile-build/Makefile`

The DNSTT bridge confirms that SlipNet creates the Go binding through `Mobile.newClient(dnsAddr, tunnelDomain, publicKey, listenAddr)`, configures protocol options, starts the client, and checks its running state. The local SOCKS listener check is only a local readiness check; it is not proof of a remote tunnel handshake or application traffic.

The Slipstream bridge confirms a separate JNI client with native start/stop/running/QUIC-ready methods. The HEV component is a separate TUN-to-SOCKS layer. The VPN service coordinates these components; copying only the Go or Rust library would not reproduce a working full-device tunnel.

The matching `.gitmodules` declares separate repositories for DNSTT, DNSTT mobile bindings, NoizDNS, VayDNS and Rust Slipstream components. The gomobile Makefile builds its Lite AAR from NoizDNS, VayDNS and Soar packages; it is not a minimal DNSTT-only artifact. Therefore, do not treat the prebuilt APK's `libgojni.so` as a drop-in dependency for SniperX.

### Updated integration direction

- Use the exact `v2.5.6` source revision as the compatibility reference.
- Resolve and pin every required submodule gitlink and its independent license before building.
- Build a minimal DNSTT binding for SniperX rather than importing SlipNet's broad Go AAR.
- Port only the required lifecycle and protocol configuration into a SniperX-owned adapter; do not copy the entire SlipNet VPN service or app architecture.
- Keep Slipstream and HEV/TUN as separately gated integrations after DNSTT is reproducibly built and verified.


## DNSTT mobile dependency investigation

At the APK-matching SlipNet commit, the `dnstt-mobile` submodule is pinned to gitlink `b265e04c40835ec1bacd447fff79169e6c2c2233`. The configured submodule URL is `https://github.com/anonvector/dnstt-mobile.git`, but that repository/revision is not currently retrievable through the public GitHub repository API (404). This is a source-provenance blocker: a similarly named fork is not automatically an acceptable replacement.

The matching Kotlin bridge calls `mobile.Mobile.newClient(...)` and methods on `mobile.DnsttClient`, including start/stop, running state, resolver mode, RR spread, authoritative mode, payload and SOCKS proxy configuration. The matching app tree also contains `app/libs/dnstt-sources.jar` (9,780 bytes) and a legacy `golibs.aar`; however, the current Gradle flavor dependencies point to `golibs-full.aar` / `golibs-lite.aar`. The checked-in gomobile Makefile builds those flavor AARs from NoizDNS, VayDNS and Soar (plus Snowflake for Full), and does not list `dnstt-mobile`. Therefore, the DNSTT binding appears to be supplied by a separate/legacy artifact path and must be resolved from its exact build provenance before reuse.

Public repositories named `dnstt-mobile` were found under unrelated owners. Their current source includes a broadly similar `mobile` package, but no matching pinned commit has been established. These forks are leads for source comparison only; none has been imported or treated as the authoritative source.

### Current decision

- Do not substitute a similarly named fork or old AAR for the pinned submodule without a commit-level source comparison.
- Do not add the legacy `golibs.aar` or APK-extracted `libgojni.so` to SniperX.
- Continue by resolving the original DNSTT mobile source and its build recipe, or independently implement a narrowly scoped gomobile binding from the pinned DNSTT core source with a documented provenance/license chain.
- Keep the SniperX E2E engine in its unavailable/not-configured state until the Android binding is built and verified against a controlled DNSTT server with real application traffic.
