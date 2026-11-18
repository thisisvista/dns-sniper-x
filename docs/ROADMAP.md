# Roadmap

## Stage 0 — Repository and Android foundation

- [x] Create the independent GitHub repository.
- [x] Add the initial Android Gradle project structure.
- [x] Add a basic Compose screen and project documentation.
- [ ] Add and verify the Gradle wrapper.
- [ ] Run the first Android build in CI and fix any build issues.

## Stage 1 — Resolver data foundation

- [ ] Define a validated resolver model.
- [ ] Add Room / SQLite persistence and migration strategy.
- [ ] Add manual single and bulk import (IP, optional port, and transport where supplied).
- [ ] Normalize and deduplicate records without losing source information.
- [ ] Add local search, filters, and history views.

## Stage 2 — Collection

- [ ] Implement configurable source adapters.
- [ ] Record source URL, retrieval time, and license/terms notes.
- [ ] Add safe download limits, parsing validation, and deduplication.
- [ ] Provide clear errors when sources are unreachable.

## Stage 3 — DNS diagnostics

- [ ] Implement UDP DNS query tests.
- [ ] Implement TCP DNS query tests independently.
- [ ] Evaluate DoH and DoT as separate transports.
- [ ] Add timeouts, bounded concurrency, cancellation, and latency measurements.
- [ ] Test on real Wi-Fi and mobile networks.

## Stage 4 — Tunnel compatibility

- [ ] Audit Findns source packages and dependencies.
- [ ] Evaluate DNSTT client availability on Android ARM64.
- [ ] Evaluate Slipstream client build and runtime feasibility on Android.
- [ ] Build controlled end-to-end tests only when a valid test server is available.
- [ ] Never report basic DNS success as tunnel success.

## Stage 5 — Export and usability

- [ ] Confirm SlipNet's accepted import format from its source/documentation.
- [ ] Add grouped export with validation and a clear compatibility label.
- [ ] Add share/save actions and export history.
- [ ] Test imports in SlipNet on real devices.

## Stage 6 — Release readiness

- [ ] Add automated unit and instrumentation tests.
- [ ] Test on a range of Android versions and device memory sizes.
- [ ] Add privacy and network-use disclosures.
- [ ] Produce signed release builds and publish release notes.
