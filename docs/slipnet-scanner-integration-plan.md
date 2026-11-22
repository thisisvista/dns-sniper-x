# SlipNet scanner integration plan

## Goal

Keep DNS SniperX as the owner of discovery, source provenance, deduplication, persistence, scheduling, resource policy, history, and exports. Reuse or adapt SlipNet's real resolver-scanning and tunnel E2E implementation for protocol compatibility and tunnel verification.

## Non-goals

- Do not replace the SniperX application or redesign its UI during infrastructure work.
- Do not delete existing collectors, provider/ASN discovery, TXT/CSV imports, DNS/DoH/DoT diagnostics, history, pause/resume, or SlipNet export.
- Do not label DNS response, UDP/TCP reachability, EDNS, random-QNAME response, or a tunnel-shaped DNS probe as E2E PASS.
- Do not import historical source verdicts as trusted current scan results.

## Pipeline contract

1. Collect candidate endpoints from registered public feeds, country/ISP allocation datasets, configured network DNS, and user imports.
2. Parse incrementally, validate endpoint syntax, deduplicate by normalized address+port, and retain every source/evidence association.
3. Persist candidates before probing. New candidates begin as NOT_TESTED.
4. Run DNS health and UDP/TCP reachability diagnostics under SniperX's adaptive resource policy.
5. Run tunnel-compatibility diagnostics only for candidates that pass the appropriate DNS checks.
6. Run real E2E only when a valid, user-imported tunnel profile is available. Store tunnel type, phase, timings, verification mode, and failure reason.
7. Set E2E PASS only after the selected SlipNet engine establishes the real tunnel and the configured proof succeeds (handshake-only for fast mode, or HTTP/SSH request through the tunnel for full verification). UI labels must distinguish these modes.
8. Keep results, provenance, history, and exports in SniperX's existing Room database and APIs.

## Verified upstream scanner behavior

Repository: https://github.com/anonvector/SlipNet  
License: AGPL-3.0; review and satisfy source, notice, and distribution obligations before shipping any derivative or linked combined work.

- `ResolverScannerRepositoryImpl` contains resolver protocol checks and the real E2E dispatcher.
- `testResolverE2e` and `testResolverE2eIsolated` dispatch DNSTT, NoizDNS, VayDNS, and Slipstream tunnel types.
- DNSTT/NoizDNS use Go mobile bindings; VayDNS uses its Go mobile client; Slipstream uses a Rust JNI library and a singleton bridge.
- Slipstream E2E waits for QUIC readiness, then in full mode verifies HTTP through the local SOCKS endpoint (or SSH for SSH variants).
- DNSTT/NoizDNS/VayDNS E2E start the actual client and verify the tunnel handshake; full mode additionally verifies HTTP or SSH through the tunnel.
- Slipstream E2E concurrency must remain 1 because its native bridge is a singleton. Other upstream E2E paths advertise a cap of 10, but SniperX must apply its own lower, battery/thermal-aware cap.
- The upstream `DnsPoolScanner` has a much higher discovery concurrency (phase 1 up to 70, phase 2 up to 20); these settings must NOT be copied into SniperX. SniperX's low-resource policy remains authoritative.

## Integration constraints

The upstream scanner is not a drop-in Kotlin file. It depends on upstream domain models, tunnel bridges, Go mobile bindings, a Rust JNI library, SSH verification helpers, Android resources, and other application classes. SniperX currently has no Hilt setup, Rust Android Gradle plugin, Go mobile packages, or Slipstream native library. Copying only the repository implementation would not compile and could create false E2E results.

Before integration, inventory the exact upstream source/native artifacts and licenses, then isolate the smallest reusable engine behind a SniperX-owned interface. Prefer an independently testable engine boundary and keep all existing SniperX data/storage/collector implementations. Do not introduce an upstream app-wide dependency or replace the SniperX application module merely to obtain the scanner.

## Required result states

- NOT_TESTED
- DNS_PASS / DNS_FAIL
- UDP_PASS / UDP_FAIL
- TCP_PASS / TCP_FAIL
- TUNNEL_CANDIDATE
- E2E_HANDSHAKE_PASS / E2E_HANDSHAKE_FAIL
- E2E_FULL_PASS / E2E_FULL_FAIL
- E2E_NOT_CONFIGURED

A resolver can pass UDP/TCP while failing a tunnel test. A candidate IP inside an ISP ASN/range is not proof that the IP belongs to that ISP or operates a DNS resolver.

## Preservation checklist

Keep existing SniperX source collection, Iran/ISP prefix discovery, provider evidence, current-network DNS capture, TXT/CSV import, streaming batch processing, deduplication, Room persistence, adaptive concurrency, pause/resume, logs, DoH/DoT checks, DNS diagnostics, history, and SlipNet-compatible export. Any future removal requires evidence that a feature is truly redundant or broken and a regression test showing its replacement preserves behavior.

## Test gates

- Unit tests for each scanner adapter, profile validation, result-state transitions, and persistence.
- Instrumentation tests for import, deduplication, pause/resume, and database persistence.
- Engine tests with controlled test servers for DNS transport and tunnel startup/failure handling.
- Physical-device tests on multiple real networks/ISPs before claiming operator coverage or real-world E2E success.
- CI green before user-facing UI changes.


## Pinned upstream build audit (2026-11-22)

The source-level dependency audit was performed against SlipNet commit
`2ed00fd0072ad0b558e30ec6b5e8b30948f98894`. Pin this revision in any future
vendor/import work; do not silently track upstream `main`.

Confirmed Android/native build requirements in that revision:

- The upstream Android app applies the Mozilla Rust Android Gradle plugin and
  builds a Rust `slipstream` shared library from
  `app/src/main/rust/slipstream-rust`.
- Its Rust build targets Android ARM, ARM64, and x86_64 and uses the Android
  NDK (upstream pins NDK 29.0.14206865), Rust stable, a custom linker wrapper,
  and a Picoquic native build.
- The Slipstream build expects Android OpenSSL 3.0.15 static libraries and
  headers for supported ABIs. This is a material native build dependency, not
  satisfied by adding a Kotlin source file.
- DNSTT/NoizDNS/VayDNS bindings are produced separately with gomobile from
  `gomobile-build`; its module declares Go 1.27.3 and builds AARs for Android
  ARM, ARM64, and AMD64. The Makefile also includes multiple local Go modules
  (DNSTT, NoizDNS, VayDNS and related mobile wrappers).
- Upstream's application module also brings its own native TUN/SOCKS components
  and service integration. SniperX must not import these wholesale just to run
  a resolver check.

### Integration decision

Do not add the upstream Rust plugin, Go toolchain, OpenSSL downloads, native
TUN stack, or large AARs to SniperX's app module until the engine boundary and
minimal required protocol set are agreed and reproducibly buildable in CI.
First isolate the DNSTT E2E path and its Go mobile artifact as a separately
versioned adapter; keep Slipstream disabled as E2E_NOT_CONFIGURED until its
Rust/Picoquic/OpenSSL build can be added and tested independently. This staged
approach avoids increasing APK size or breaking the current app before a real
engine can be validated.

No native engine was added by this audit. The existing
`SlipNetE2eEngine` remains an integration contract only, and the application
must continue to report E2E_NOT_CONFIGURED until a real adapter is installed.


### DNSTT Go binding boundary (verified against the pinned revision)

The pinned SlipNet revision declares DNSTT-related components as Git submodules
in its root `.gitmodules`: `dnstt-mobile`, `dnstt`, and `noizdns` are separate
repositories, not ordinary source directories owned by the SlipNet tree. The
Android bridge imports the generated Go package as `mobile.Mobile` and creates
its client with `newClient(dnsAddr, tunnelDomain, publicKey, listenAddr)`.
The pinned `gomobile-build/Makefile` generates `golibs-full.aar` and
`golibs-lite.aar` from the `noizdns/mobile`, `vaydns-mobile/vaydns`,
`soar-mobile/soar`, and (full only) `snowflake-mobile/snowflake` packages.
Even the upstream Lite artifact is not a DNSTT-only build: it still includes
multiple mobile packages. The Go module also pins Go 1.27.3 and local
submodule replacements.

Consequences for SniperX:

- Do not add a guessed Maven coordinate or assume `mobile.Mobile` exists in a
  standalone AAR. It is generated by gomobile from the upstream module graph.
- Before building a minimal artifact, pin and inspect the exact gitlink commits
  for `dnstt-mobile`, `dnstt`, and `noizdns`, then map their module imports and
  licenses. A branch name or the current upstream HEAD is not a reproducible pin.
- The smallest plausible integration is a separately versioned Go mobile
  adapter that exports only the DNSTT client lifecycle and the methods needed
  for tunnel verification. Its AAR must be built for SniperX's supported ABIs,
  tested on Android, and tracked with source/license notices.
- Keep the Kotlin engine unavailable until that artifact is present and a
  controlled resolver + DNSTT server test proves startup, tunnel handshake,
  application traffic, timeout, cancellation, and cleanup. Merely observing
  the local SOCKS listener is not proof that the remote tunnel works.

This audit confirms the Kotlin bridge shape and build packaging, but does not
claim that a standalone DNSTT-only AAR has already been built or validated.
