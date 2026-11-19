# Architecture

## Product boundary

DNS SniperX is an independent Android application. SlipNet is a possible destination for exported resolver results; DNS SniperX does not depend on SlipNet being installed.

## Layers

| Layer | Responsibility | Status |
|---|---|---|
| UI | Compose screens, progress, result details | Local import, public collection and UDP/TCP scan controls |
| Collector | Retrieve resolver candidates from configured sources | Reviewed HTTPS source list; endpoint deduplication and source-ID merging |
| Domain | Resolver model, parsing and test outcomes | Endpoint parser and per-transport outcomes |
| Storage | Room / SQLite database | Resolver library, per-transport outcomes, source provenance, and scan history (schema v4) |
| Scanner | DNS transport probes | UDP and TCP query probes; EDNS and NXDOMAIN diagnostics |
| Tunnel tests | Real DNSTT / Slipstream end-to-end checks | Not implemented; Android client feasibility must be verified |
| Export | Versioned formats and grouped export | Endpoint TXT export implemented; SlipNet parser compatibility unverified |

## DNS probe behaviour

The scanner sends a small recursive A query for `example.com` to each saved endpoint over UDP and TCP. It checks the DNS transaction ID, response bit and opcode, and verifies that the response echoes the expected question. UDP and TCP results, latency, and last-tested time are stored separately.

Scanning is processed in batches of up to 500 saved entries, with at most 8 resolvers active concurrently and a 1.8 second socket timeout per network operation. Completed results are persisted as scanning progresses. Pause/resume continues with remaining untested entries.

The test runs through Android's currently active network route. Results may differ between mobile operators, Wi-Fi networks, locations and times. The probe does not perform broad address-range scanning.

A valid DNS response proves only DNS reachability on that transport. It does not establish tunnel delegation, EDNS compatibility, DNSTT/Slipstream support, or end-to-end connectivity.

## Result meanings

1. **DNS reachability:** a valid response to the test DNS question was received.
2. **Tunnel compatibility:** requires additional protocol-specific checks; not implemented yet.
3. **End-to-end success:** requires a real client and controlled test server; not implemented yet.

## Scan history\n\nEach completed resolver probe appends a local history row containing the endpoint, test timestamp, UDP/TCP outcomes and latencies, and whether it was part of a first scan or retry. The database retains the latest 100 records in the history query; the dashboard displays the latest 30. History is stored locally and is not exported or synchronized.\n\n## Resolver source provenance

Resolver identity is the normalized address plus port. When the same endpoint is found in multiple collector sources, the primary source and additional source IDs are retained rather than inserting duplicate rows. Source URLs, retrieval timestamps, and license/terms metadata are not yet persisted per resolver.

## Database migration

Schema version 3 adds the `additionalSources` field while retaining the existing UDP/TCP outcomes. Existing version 2 databases are migrated in place; the earlier version 1 to version 2 migration remains registered.

## Safety and network behaviour

- Use public lists, user-provided imports, and sources whose terms permit collection.
- Apply concurrency limits, timeouts, and rate limits.
- Do not perform broad, indiscriminate IP-range scans.
- A scan result applies only to the network on which it was tested.
- Offline mode allows browsing the local library; it cannot discover new external resolvers or prove external connectivity without a route.
