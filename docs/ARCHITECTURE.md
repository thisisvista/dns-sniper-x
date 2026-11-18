# Architecture

## Product boundary

DNS SniperX is an independent Android application. SlipNet is a possible destination for exported resolver results; DNS SniperX does not depend on SlipNet being installed.

The first release targets Android only. Keep core logic independent from Android UI APIs where practical so a future desktop implementation can reuse tested logic.

## Planned layers

| Layer | Responsibility | Initial status |
|---|---|---|
| UI | Compose screens, progress, filters, result details | Starter screen only |
| Collector | Import files and retrieve resolver candidates from configured sources | Not implemented |
| Domain | Resolver model, test outcomes, ranking rules | To be designed |
| Storage | Room / SQLite database and scan history | Not implemented |
| Scanner | DNS transport probes and staged diagnostics | Not implemented |
| Tunnel tests | Real DNSTT / Slipstream end-to-end checks | Feasibility review required |
| Export | Versioned formats and grouped export | Not implemented |

## Result model: keep the meanings separate

A resolver must not be labelled "tunnel working" just because it answers a DNS query.

1. **DNS reachability:** a valid DNS response was received using a specific transport.
2. **Tunnel compatibility:** the resolver passed the relevant protocol-specific checks.
3. **End-to-end success:** a real test tunnel completed against a controlled, valid test server and client configuration.

Each result should include the test time, network context, transport, latency, and failure reason where available. Results are time-sensitive and should be re-tested rather than permanently discarded.

## Resolver records

The planned record should retain at least:

- address and port;
- transport tested (UDP, TCP, DoH, or DoT);
- source and collection time;
- test timestamp and latency;
- network type and operator label, when reliably available;
- DNS reachability result;
- tunnel compatibility result;
- end-to-end result;
- last failure reason and a history of previous outcomes.

An IP address alone does not identify its transport. UDP and TCP tests must be recorded independently.

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

No Findns code is included in this initial scaffold. Reuse is a later, evidence-based decision.
