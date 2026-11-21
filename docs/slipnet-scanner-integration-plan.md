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
