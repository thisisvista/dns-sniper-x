# Roadmap

## Stage 0 — Repository and Android foundation

- [x] Create the independent GitHub repository.
- [x] Add the initial Android Gradle project structure.
- [ ] Add and verify the Gradle wrapper.
- [x] Run Android builds in CI and fix build issues.

## Stage 1 — Resolver data foundation

- [x] Define and validate resolver endpoints (IPv4, IPv6, hostnames, and optional ports).
- [x] Add Room / SQLite persistence and a migration strategy.
- [x] Add manual paste and bulk TXT/CSV import.
- [x] Normalize and deduplicate resolver endpoints.
- [x] Add local search and incremental list display.
- [x] Preserve source IDs when the same resolver appears in multiple sources.
- [ ] Add dedicated scan history and richer filters.

## Stage 2 — Collection

- [x] Collect candidates from reviewed public HTTPS sources, including a regional Iran list.
- [x] Add download size/time limits, syntax validation, and deduplication.
- [x] Report source download errors.
- [ ] Make source adapters configurable.
- [ ] Record source URL, retrieval time, and license/terms notes for each source.

## Stage 3 — DNS diagnostics

- [x] Implement independent UDP and TCP DNS probes.
- [x] Add timeouts, bounded concurrency, cancellation/pause-resume, and latency measurements.
- [x] Add basic NXDOMAIN and EDNS payload diagnostics.
- [ ] Evaluate and implement DoH and DoT as separate transports.
- [ ] Complete real-device testing on Wi-Fi and mobile networks.

## Stage 4 — Tunnel compatibility

- [ ] Audit Findns source packages and dependencies without copying its code.
- [ ] Evaluate DNSTT client availability on Android ARM64.
- [ ] Evaluate Slipstream client build and runtime feasibility on Android.
- [ ] Build controlled end-to-end tests only when a valid test server and required credentials/certificates are available.
- [ ] Never report basic DNS success as tunnel success.

## Stage 5 — Export and usability

- [x] Export resolvers with at least one successful UDP/TCP test, preserving non-default ports and IPv6 formatting.
- [ ] Confirm SlipNet's accepted import format from its source/documentation.
- [ ] Add grouped export with validation and a clear compatibility label.
- [ ] Add share/save actions and export history.
- [ ] Test imports in SlipNet on real devices.

## Stage 6 — Release readiness

- [x] Add automated unit tests and a CI debug-APK build.
- [ ] Add instrumentation tests.
- [ ] Test on a range of Android versions and device memory sizes.
- [ ] Add privacy and network-use disclosures.
- [ ] Produce signed release builds and publish release notes.
