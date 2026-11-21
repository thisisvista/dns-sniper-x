# Roadmap

## Stage 0 — Repository and Android foundation
- [x] Create the independent Android project and GitHub repository.
- [x] Add and verify the Gradle wrapper.
- [x] Run Android builds and unit tests in CI.

## Stage 1 — Resolver data foundation
- [x] Parse and validate IPv4, IPv6, hostname, and optional-port resolver entries.
- [x] Persist resolver records and scan outcomes with Room (schema v9).
- [x] Import large TXT files in automatic batches of 500 with pause/resume.
- [x] Normalize and deduplicate endpoints while merging source provenance.
- [x] Search, sort, filter, and incrementally display the local resolver library.
- [x] Keep scan, DoH, DoT, and source-collection histories locally.
- [x] Add bounded local diagnostic logs with search, severity filters, redacted error details, TXT export, and clear controls (schema v9).

## Stage 2 — Public source collection
- [x] Collect from reviewed public HTTPS lists, including a regional Iran list.
- [x] Bound connection/read time, response characters, and lines per source.
- [x] Enforce response character limits while reading, including oversized single-line responses.
- [x] Reject sources that exceed the line limit instead of silently accepting truncated content.
- [x] Restrict source URLs and every redirect target to the reviewed HTTPS host allowlist.
- [x] Fetch reviewed sources sequentially to keep network and memory use bounded; report per-source failures.
- [ ] Make source adapters configurable without weakening URL policy.
- [x] Persist source ID/name/URL, retrieval time, candidate count, fetch errors, and nullable terms notes in local history (schema v9).

## Stage 3 — DNS diagnostics
- [x] Independent UDP and TCP DNS probes with bounded concurrency and timeout.
- [x] EDNS and NXDOMAIN anomaly diagnostics.
- [x] Separate DoH and DoT connectivity diagnostics and persistent histories.
- [ ] Complete real-device tests on Wi-Fi and mobile networks.

## Stage 4 — Tunnel compatibility
- [x] Evaluate Android DNSTT client feasibility.
- [x] Evaluate Android Slipstream client feasibility.
- [x] Add protocol-specific tunnel profile metadata validation (no secret persistence).
- [x] Add a pure tunnel lifecycle state machine with transition tests.
- [x] Add an Android Keystore-backed AES-GCM secret vault foundation (not yet connected to profiles or UI).
- [x] Pass emulator instrumentation coverage for vault encryption/decryption, AAD binding, tamper rejection, deletion, and lost-key behavior.
- [x] Clear temporary ciphertext, IV, and AAD byte buffers after use in the vault.
- [ ] Verify Keystore behavior across supported physical Android devices and API levels.
- [ ] Integrate secret provisioning with protocol-specific configuration.
- [ ] Implement and audit native DNSTT/Slipstream client adapters.
- [ ] Implement Android VpnService lifecycle and routing.
- [ ] Implement controlled end-to-end checks only with a valid test server and required credentials/certificates.
- [ ] Never infer tunnel success from ordinary DNS reachability.

## Stage 5 — Export and interoperability
- [x] Export successful resolver endpoints with non-default ports and IPv6 formatting.
- [x] Verify SlipNet accepted import formats against its source/documentation.
- [x] Add dedicated SlipNet scanner TXT export: working IPv4 resolvers on port 53, one IP per line.
- [ ] Test exported files with SlipNet on real devices.

## Stage 6 — Release readiness
- [x] Automated unit tests and debug APK CI artifact.
- [ ] Complete physical-device/API-level and Wi-Fi/mobile network tests (Keystore vault instrumentation is covered by CI).
- [x] In-app privacy/network-use disclosures and README documentation.
- [ ] Signed release build and release notes.
