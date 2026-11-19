# Architecture

## Product boundary

DNS SniperX is an independent Android application. SlipNet is a possible destination for exported resolver results; it is not a runtime dependency.

## Layers

| Layer | Responsibility |
|---|---|
| UI | Compose screens, progress, resolver list, filters, histories and controls |
| Collector | Reviewed public HTTPS source downloads, parsing, deduplication and provenance |
| Domain | Resolver endpoint model, parser and per-transport outcomes |
| Storage | Room database: resolver library, source provenance, scan history, DoH history and DoT history (schema v6) |
| Scanner | UDP/TCP DNS probes, EDNS/NXDOMAIN diagnostics, separate DoH and DoT checks |
| Tunnel tests | Real DNSTT/Slipstream end-to-end checks; not implemented |
| Export | Resolver TXT export; SlipNet import compatibility still requires verification |

## Public source collection

The collector currently uses four reviewed public lists: a regional Iran list, Public DNS Info, Trickest resolvers, and a global public DNS directory. Sources are fetched concurrently; the regional source is processed first so its provenance is retained when duplicate endpoints are merged.

The collector accepts HTTPS URLs only from the explicit host allowlist `public-dns.info` and `raw.githubusercontent.com`. Automatic redirects are disabled. Each redirect is resolved and validated against the same HTTPS, hostname, default-port, and no-user-info policy before another request is made. Redirects are limited to three hops. This prevents a reviewed source from redirecting collection traffic to an arbitrary host or downgrading to HTTP.

Downloads use bounded connect/read timeouts and per-source character and line limits. Parsing and endpoint validation occur before candidates are returned to storage. Failed sources are reported independently; one unavailable source does not prevent the others from being processed.

## Resolver identity and provenance

A resolver endpoint is identified by normalized address plus port. Duplicate discoveries merge source IDs into the existing record rather than creating duplicate rows. The regional list is ordered first to preserve useful regional provenance. The database currently stores source IDs, but not the original source URL, retrieval timestamp, or source license/terms notes per record.

## DNS probe behaviour

The scanner sends a recursive A query for `example.com` over UDP and TCP independently. It validates transaction identity and the returned question. Results and latency are stored separately by transport. Scans are processed in batches of up to 500 entries, with at most eight active resolvers and a bounded socket timeout. Results are persisted as scanning progresses and the scan can be paused and resumed.

A valid DNS response proves reachability for that transport only. It does not prove censorship circumvention, tunnel delegation, EDNS compatibility, or DNSTT/Slipstream end-to-end support.

## DoH and DoT

DoH checks use RFC 8484 HTTPS POST against a fixed provider allowlist. DoT checks use TLS on port 853 with hostname verification and the platform trust store. These diagnostics and their histories are separate from ordinary UDP/TCP resolver results; neither is an end-to-end tunnel test.

## Storage and migrations

Room schema version 6 contains resolver records, scan history, DoH history, and DoT history. Registered migrations cover versions 1→2 through 5→6. Histories are stored locally.

## Offline behaviour

Offline mode can display the locally stored resolver library and prior results. Discovering new public resolvers or validating live network reachability requires internet connectivity.

## Network and privacy boundaries

- Collection is limited to reviewed public sources and user-provided imports.
- Source URLs and redirect targets are validated against a fixed HTTPS host allowlist.
- Downloads, concurrency, and response sizes are bounded.
- User-assigned ISP labels are descriptive labels, not verified network ownership.
- A scan result applies only to the network and time at which it was measured.
