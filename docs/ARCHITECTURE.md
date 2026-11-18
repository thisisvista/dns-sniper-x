# Architecture

## Product boundary

DNS SniperX is an independent Android application. SlipNet is a possible destination for exported resolver results; DNS SniperX does not depend on SlipNet being installed.

The first release targets Android only. Keep core logic independent from Android UI APIs where practical so a future desktop implementation can reuse tested logic.

## Layers and current status

| Layer | Responsibility | Status |
|---|---|---|
| UI | Compose screens, progress, filters, result details | Starter UI with local import and public list collection |
| Collector | Import files and retrieve resolver candidates from configured sources | Manual import and HTTPS source fetcher implemented |
| Domain | Resolver model, parsing and test outcomes | Resolver record and endpoint parser implemented |
| Storage | Room / SQLite database and scan history | Room database implemented; scan history fields reserved |
| Scanner | DNS transport probes and staged diagnostics | Not implemented |
| Tunnel tests | Real DNSTT / Slipstream end-to-end checks | Feasibility review required |
| Export | Versioned formats and grouped export | Not implemented |

## Public source collection

The collector uses a small, reviewed HTTPS allowlist:

- Public DNS Info CSV
- Trickest Resolvers plain-text list

Each source is downloaded independently with connection and read timeouts. A failed source does not cancel other source results. Response size and line count are capped to protect memory. The source identifier is saved on each imported resolver. Lists are untrusted candidate data: collection does not mean an endpoint is live, public, safe, or compatible with a tunnel.

## Local resolver record

Each saved record contains address, port, source, collection time, status, optional last-test time, latency and note. The database deduplicates by address and port. An IP address does not identify a transport, so UDP and TCP results must be modelled separately when scanning is added.

## Result model: keep the meanings separate

A resolver must not be labelled "tunnel working" just because it answers a DNS query.

1. **DNS reachability:** a valid DNS response was received using a specific transport.
2. **Tunnel compatibility:** the resolver passed the relevant protocol-specific checks.
3. **End-to-end success:** a real test tunnel completed against a controlled, valid test server and client configuration.

Each result should include the test time, network context, transport, latency, and failure reason where available. Results are time-sensitive and should be re-tested rather than permanently discarded.

## Scanner safety and network behaviour

- Use public lists, user-provided imports, and sources whose terms permit collection.
- Deduplicate candidates but retain source provenance.
- Apply concurrency limits, timeouts, and rate limits.
- Do not perform broad, indiscriminate IP-range scans.
- Do not infer country or operator from an address unless the source is reliable.
- A scan result applies to the network on which it was tested; results can differ by SIM operator, Wi-Fi, location, and time.

## Offline behaviour

Offline mode means the user can browse and manage the local resolver database. It does not mean the app can discover new internet resolvers or prove external connectivity while no route to those servers exists.

## Findns evaluation

Findns may provide useful Go DNS-scanning logic, but it is not an Android application. Before adapting any code:

1. inspect the exact source files and dependency graph;
2. identify which packages are independent of CLI/TUI code;
3. verify Android ARM64 build feasibility and runtime behaviour;
4. retain the MIT license and copyright notice for any reused code;
5. compare its checks with the app's explicit result categories.

No Findns code is included in this implementation. Reuse is a later, evidence-based decision.
