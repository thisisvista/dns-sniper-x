# Roadmap

## Stage 0 — Repository and Android foundation

- [x] Create the independent Android project and GitHub repository.
- [ ] Add and verify the Gradle wrapper.
- [x] Run Android builds and unit tests in CI.

## Stage 1 — Resolver data foundation

- [x] Parse and validate IPv4, IPv6, hostname, and optional-port resolver entries.
- [x] Persist resolver records and scan outcomes with Room (schema v7).
- [x] Import large TXT files in automatic batches of 500 with pause/resume.
- [x] Normalize and deduplicate endpoints while merging source provenance.
- [x] Search, sort, filter, and incrementally display the local resolver library.
- [x] Keep scan, DoH, DoT, and source-collection histories locally.

## Stage 2 — Public source collection

- [x] Collect from reviewed public HTTPS lists, including a regional Iran list.
- [x] Bound connection/read time, response characters, and lines per source.
- [x] Enforce response character limits while reading, including oversized single-line responses.
- [x] Reject sources that exceed the line limit instead of silently accepting truncated content.
- [x] Restrict source URLs and every redirect target to the reviewed HTTPS host allowlist.
- [x] Fetch independent sources concurrently and report per-source failures.
- [ ] Make source adapters configurable without weakening URL policy.
- [x] Persist source ID/name/URL, retrieval time, candidate count, fetch errors, and nullable terms notes in local history (schema v7). Terms remain "not documented" until verified.

## Stage 3 — DNS diagnostics

- [x] Independent UDP and TCP DNS probes with bounded concurrency and timeout.
- [x] EDNS and NXDOMAIN anomaly diagnostics.
- [x] Separate DoH and DoT connectivity diagnostics and persistent histories.
- [ ] Complete real-device tests on Wi-Fi and mobile networks.

## Stage 4 — Tunnel compatibility

- [ ] Evaluate Android DNSTT client feasibility.
- [ ] Evaluate Android Slipstream client feasibility.
- [ ] Implement controlled end-to-end checks only with a valid test server and required credentials/certificates.
- [ ] Never infer tunnel success from ordinary DNS reachability.

## Stage 5 — Export and interoperability

- [x] Export successful resolver endpoints with non-default ports and IPv6 formatting.
- [x] Verify SlipNet's accepted import formats against its source/documentation (scanner TXT: IPs per line or comma-separated; profile resolvers: host or host:port, comma/newline-separated, maximum 10; IPv6 is rejected in profile resolver input).
- [x] Add a dedicated SlipNet scanner TXT export: working IPv4 resolvers on port 53, one IP per line; exclude IPv6, non-default ports, and duplicates.
- [ ] Test exported files with SlipNet on real devices.

## Stage 6 — Release readiness

- [x] Automated unit tests and debug APK CI artifact.
- [ ] Instrumentation and real-device tests.
- [ ] Privacy/network-use disclosures.
- [ ] Signed release build and release notes.
