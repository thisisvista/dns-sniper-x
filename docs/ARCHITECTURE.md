# Architecture

## Product boundary

DNS SniperX is an independent Android application. SlipNet is a possible destination for exported resolver results; DNS SniperX does not depend on SlipNet being installed.

## Layers

| Layer | Responsibility | Status |
|---|---|---|
| UI | Compose screens, progress, result details | Local import, public collection and UDP/TCP scan controls |
| Collector | Retrieve resolver candidates from configured sources | HTTPS allowlist implemented |
| Domain | Resolver model, parsing and test outcomes | Endpoint parser and per-transport outcomes |
| Storage | Room / SQLite database | Resolver library and migration to scan-result columns |
| Scanner | DNS transport probes | UDP and TCP query probes implemented |
| Tunnel tests | Real DNSTT / Slipstream end-to-end checks | Not implemented; Android client feasibility must be verified |
| Export | Versioned formats and grouped export | Not implemented |

## DNS probe behaviour

The scanner sends a small recursive A query for `example.com` to each saved endpoint over UDP and TCP. It checks the DNS transaction ID, response bit and opcode, and records separate status and latency for each transport. A maximum of 100 entries are processed per run, with at most 8 resolver probes active concurrently and a 1.8 second socket timeout.

The test runs through Android's currently active network route. Results may differ between mobile operators, Wi-Fi networks, locations and times. The probe does not perform broad address-range scanning.

A valid DNS response proves only DNS reachability on that transport. It does not establish tunnel delegation, EDNS compatibility, DNSTT/Slipstream support, or end-to-end connectivity.

## Result meanings

1. **DNS reachability:** a valid response to the test DNS question was received.
2. **Tunnel compatibility:** requires additional protocol-specific checks; not implemented yet.
3. **End-to-end success:** requires a real client and controlled test server; not implemented yet.

## Database migration

Schema version 2 adds independent UDP and TCP status/latency columns while retaining the original aggregate fields. Existing version 1 user databases are migrated in place.

## Safety and network behaviour

- Use public lists, user-provided imports, and sources whose terms permit collection.
- Apply concurrency limits, timeouts, and rate limits.
- Do not perform broad, indiscriminate IP-range scans.
- A scan result applies only to the network on which it was tested.
- Offline mode allows browsing the local library; it cannot discover new external resolvers or prove external connectivity without a route.
