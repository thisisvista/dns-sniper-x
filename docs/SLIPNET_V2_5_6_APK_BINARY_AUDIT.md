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
