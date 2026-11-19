# Architecture

## Product boundary
DNS SniperX is an independent Android application. SlipNet is a possible destination for exported resolver results; it is not a runtime dependency.

## Layers
| Layer | Responsibility |
|---|---|
| UI | Compose screens, progress, resolver list, filters, histories and controls |
| Collector | Reviewed public HTTPS source downloads, parsing, deduplication and provenance |
| Domain | Resolver endpoint model, parser, per-transport outcomes, tunnel profile metadata and lifecycle state |
| Storage | Room database: resolver library, source collection history, scan history, DoH history and DoT history (schema v7) |
| Scanner | UDP/TCP DNS probes, EDNS/NXDOMAIN diagnostics, separate DoH and DoT checks |
| Tunnel adapters | Future DNSTT/Slipstream native clients and Android VPN integration; not implemented |
| Export | General resolver TXT export and dedicated SlipNet scanner TXT export |

## Tunnel domain model
The current tunnel foundation is deliberately non-operational. TunnelProfile validates protocol-specific public connection metadata for DNSTT and Slipstream, while TunnelStateMachine guards allowed lifecycle transitions. No tunnel sockets, native clients, Android VpnService, credentials, private keys, certificates, or secret persistence are implemented. A CONNECTED state is only a state-machine value in tests or future orchestration; it is not evidence of network connectivity.

Tunnel profiles currently contain only non-secret metadata. Secret provisioning and encrypted storage must be designed before credentials are accepted or persisted. DNSTT public-key metadata and Slipstream TLS server-name metadata are kept protocol-specific to prevent accidental configuration mixing.

## Public source collection
The collector currently uses four reviewed public lists: a regional Iran list, Public DNS Info, Trickest resolvers, and a global public DNS directory. Sources are fetched concurrently; the regional source is processed first so its provenance is retained when duplicate endpoints are merged.

The collector accepts HTTPS URLs only from the explicit host allowlist public-dns.info and raw.githubusercontent.com. Automatic redirects are disabled. Each redirect is resolved and validated against the same HTTPS, hostname, default-port, and no-user-info policy before another request is made. Redirects are limited to three hops.

Downloads use bounded connect/read timeouts and per-source character and line limits. The response reader enforces the character cap incrementally before appending each character. The line limit is enforced strictly; oversized responses are rejected rather than silently truncated. Parsing and endpoint validation occur before candidates are returned to storage.

## Resolver identity and provenance
A resolver endpoint is identified by normalized address plus port. Duplicate discoveries merge source IDs into the existing record. A separate local collection history stores source ID, display name, exact configured URL, retrieval timestamp, candidate count, and fetch error. Terms/license notes remain undocumented until verified; the app does not infer or claim a license.

## DNS probe behaviour
The scanner sends a recursive A query for example.com over UDP and TCP independently. It validates transaction identity and the returned question. Results and latency are stored separately by transport. Scans run in batches of up to 500 entries, with at most eight active resolvers and a bounded socket timeout. Results are persisted progressively and scans can be paused and resumed.

A valid DNS response proves reachability for that transport only. EDNS and reserved-domain NXDOMAIN checks are diagnostics, not proof of malicious interception. DoH and DoT checks are separate. None proves censorship circumvention, tunnel delegation, or DNSTT/Slipstream end-to-end support.

## DoH and DoT
DoH checks use RFC 8484 HTTPS POST against a fixed provider allowlist. DoT checks use TLS on port 853 with hostname verification and the platform trust store. Neither is an end-to-end tunnel test.

## Export interoperability
The general resolver export retains endpoint ports and supports bracketed IPv6. The dedicated SlipNet scanner export follows its scanner text format: unique working IPv4 addresses on port 53, one address per line. SlipNet profile resolver input is a separate format.

## Storage and migrations
Room schema version 7 contains resolver records, source collection history, scan history, DoH history, and DoT history. Registered migrations cover versions 1→2 through 6→7. Histories are stored locally.

## Offline behaviour
Offline mode can display the locally stored resolver library and prior results. Discovering new public resolvers or validating live network reachability requires internet connectivity.

## Network and privacy boundaries
- Collection is limited to reviewed public sources and user-provided imports.
- Source URLs and redirect targets are validated against a fixed HTTPS host allowlist.
- Downloads, concurrency, and response sizes are bounded.
- User-assigned ISP labels are descriptive labels, not verified network ownership.
- A scan result applies only to the network and time at which it was measured.
