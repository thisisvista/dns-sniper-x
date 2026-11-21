# Architecture

## Product boundary
DNS SniperX is an independent Android application. SlipNet is a possible destination for exported resolver results; it is not a runtime dependency or a bundled tunnel client.

## Layers
| Layer | Responsibility |
|---|---|
| UI | Compose screens, progress, resolver list, filters, histories, import and export controls |
| Collector | Sequential downloads from reviewed public HTTPS lists, bounded streaming parsing, deduplication and source provenance |
| Domain | Resolver endpoint model, parser, per-transport outcomes, tunnel profile metadata and lifecycle state |
| Storage | Room schema v9: resolver library, source-collection history, scan history, DoH/DoT histories, tunnel profile metadata and bounded diagnostic logs |
| Scanner | Independent UDP/TCP DNS probes, conditional EDNS/NXDOMAIN diagnostics, separate DoH and DoT checks |
| Tunnel foundation | DNSTT/Slipstream profile metadata, lifecycle guard and Keystore-backed secret-vault groundwork; no operational tunnel yet |
| Export | General resolver TXT export and dedicated SlipNet scanner TXT export |

## Tunnel domain model and secret handling
TunnelProfile validates protocol-specific public connection metadata for DNSTT and Slipstream. TunnelStateMachine guards allowed lifecycle transitions. Neither starts networking or establishes a VPN.

AndroidKeystoreTunnelSecretStore encrypts secret values with AES-GCM using a non-exportable key held in Android Keystore. Ciphertext is stored in app-private preferences; profile ID and secret name are authenticated as additional data. Temporary secret-related byte buffers are cleared after use, and Android app backup is disabled.

The vault is currently a foundation, not an operational credential-provisioning flow. It is not integrated with profiles, UI, native clients or a running tunnel. Emulator instrumentation tests cover encryption/decryption, AAD binding, tamper rejection, deletion and lost-key behavior. Verification on supported physical devices and API levels remains outstanding.

No tunnel sockets, native DNSTT/Slipstream clients, Android VpnService, secure provisioning flow or end-to-end connection checks are implemented. A CONNECTED state in the pure state machine is not evidence of network connectivity.

## Public source collection
The collector currently processes four reviewed public lists sequentially: a regional Iran list, Public DNS Info, Trickest resolvers and a global public DNS directory. The regional source is processed first so its provenance is retained when duplicate endpoints are merged.

The collector accepts HTTPS URLs only from the explicit host allowlist `public-dns.info` and `raw.githubusercontent.com`. Automatic redirects are disabled. Each redirect target is resolved and validated against the same HTTPS, hostname, default-port and no-user-info policy before another request is made. Redirects are limited to three hops.

Downloads use bounded connect/read timeouts and per-source character and line limits. The response reader enforces the character cap incrementally, including oversized single lines, and rejects responses that exceed the line or character limit rather than silently truncating them. Parsing occurs line by line. Parsed endpoints are persisted in batches of 500, keeping only one source and one bounded pending batch in memory. Cancellation flushes a partial pending batch before cancellation propagates.

Source adapters are currently a fixed reviewed list; making them configurable without weakening URL policy remains future work.

## Resolver identity, provenance and UI paging
A resolver endpoint is identified by normalized address plus port. Duplicate discoveries merge source IDs into the existing record. Local collection history stores source ID, display name, exact configured URL, retrieval timestamp, candidate count, fetch error and nullable terms notes. Terms/license notes are not inferred when they have not been verified.

The UI queries and displays bounded windows rather than loading the complete resolver table. The current list initially displays 100 entries and can fetch additional windows. Database-backed filtering and sorting keep the full resolver library out of UI memory.

## DNS probe behaviour
The scanner sends a recursive A query for `example.com` over UDP and TCP independently and validates transaction identity and the returned question. Results and latency are stored separately by transport. The scanner takes small batches of up to 10 resolvers from the database, uses adaptive concurrency (normally four active probes, with a hard maximum of eight), and persists each resolver result atomically as it completes. This avoids holding a large scan-result buffer in memory and supports pause/resume.

The probe timeout is bounded. UDP and TCP outcomes remain independent. Extended EDNS and reserved-domain NXDOMAIN anomaly diagnostics run only after a successful baseline response; they are diagnostics, not proof of malicious interception.

A valid DNS response proves reachability for that transport only. DoH and DoT checks are separate. None proves censorship circumvention, tunnel delegation or DNSTT/Slipstream end-to-end support.

## DoH and DoT
DoH checks use RFC 8484 HTTPS POST against a fixed provider allowlist. DoT checks use TLS on port 853 with hostname verification and the platform trust store. Both have bounded network operations and separate local histories. Neither is an end-to-end tunnel test.

## Export interoperability
The general resolver export retains non-default endpoint ports and supports bracketed IPv6. The dedicated SlipNet scanner export follows its scanner text format: unique working IPv4 addresses on port 53, one address per line. SlipNet profile resolver input is a separate format and is not conflated with scanner import.

## Diagnostic logging
Operational failures and selected warnings from collection, scanning, TXT import/export, and DoH/DoT checks are written to a local Room log. The log is bounded to the latest 1,000 entries; the UI shows the latest 500, supports severity filtering and text search, and displays sanitized error details on demand. Logs can be exported as TXT or cleared by the user. Secret-like assignments, URL user-info and URL query strings are redacted before persistence. Logging is best-effort and must never interrupt the operation being diagnosed. It is not a complete Android system/crash log and does not upload telemetry.

## Storage and migrations
Room schema version 9 contains resolver records, source-collection history, scan history, DoH history, DoT history, tunnel profile metadata and bounded diagnostic logs. Registered migrations cover versions 1→2 through 8→9. Resolver scan history and resolver status are updated transactionally. Histories and diagnostic logs are stored locally.

## Offline behaviour
Offline mode can display the locally stored resolver library and prior results. Discovering new public resolvers or validating live network reachability requires internet connectivity.

## Network, resource and privacy boundaries
- Collection is limited to reviewed public sources and user-provided imports.
- Source URLs and every redirect target are validated against a fixed HTTPS host allowlist.
- Downloads, response sizes, line lengths, scanner batches and concurrency are bounded.
- Scan concurrency adapts to thermal and battery conditions; critical thermal conditions pause scanning.
- User-assigned ISP labels are descriptive labels, not verified network ownership.
- A scan result applies only to the network and time at which it was measured.
- Real-device, weak-device, thermal and Wi-Fi/mobile network validation remains outstanding; CI and emulator tests do not replace those checks.
