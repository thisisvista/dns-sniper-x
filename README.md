# DNS SniperX

DNS SniperX is an independent Android app for collecting public DNS resolver candidates, keeping a local resolver library, testing DNS behavior, and exporting compatible resolver lists.

## Current implementation

- Android app with a local Room database (schema version 9) for resolver entries, scan history, DoH/DoT history, source-collection history, tunnel-profile metadata, and bounded diagnostic logs.
- Public resolver collection over HTTPS from a reviewed host allowlist. Sources are fetched sequentially; responses are streamed line-by-line and parsed records are persisted in batches of up to 500.
- Collection enforces connection/read timeouts, redirect-host restrictions, per-source character and line limits, and a maximum line length. A partial batch is saved when collection is cancelled or a source fails.
- Duplicate resolver endpoints are merged while retaining source provenance.
- Manual paste and TXT-file import. Large TXT files are read and saved in chunks of up to 500 lines; the user selects the file only once.
- DNS scanning tests UDP and TCP independently. The app schedules small scan batches (10 entries) and adapts parallelism to thermal state, battery level, and power-saver mode. Normal operation uses up to 4 concurrent resolvers; the probe engine has a hard concurrency ceiling of 8.
- Completed scan results are persisted individually so a pause or interruption does not discard already completed probes. Scanning supports progress, pause/resume, and retry of unsuccessful results.
- Search, sorting, filtering, and incremental display keep the full resolver library out of the UI's working set.
- The collection screen lets users choose which reviewed public sources to fetch; source URLs remain governed by the fixed HTTPS host and redirect allowlist.
- Settings includes searchable, redacted local diagnostic logs and an in-app privacy/network-use disclosure. No automatic database or log upload is implemented.
- Baseline DNS checks use `example.com A`. Extended diagnostics include EDNS payload checks and randomized names under the reserved `.invalid` TLD; these run only after a valid baseline response.
- Separate DoH (DNS over HTTPS) and DoT (DNS over TLS) connectivity diagnostics and histories are available. These checks do not establish tunnel compatibility.
- General TXT export retains non-default ports and formats IPv6 endpoints with brackets. A dedicated SlipNet scanner export emits only unique working IPv4 resolvers on port 53, one IP per line.
- Tunnel-profile metadata validation, a lifecycle state machine, and a Keystore-backed AES-GCM secret-vault foundation have tests. These are foundations only: they do not yet establish a VPN connection or implement the native DNSTT/Slipstream clients.
- GitHub Actions runs unit tests, Android Keystore instrumentation tests, builds a debug APK, and uploads it as an artifact.

## Public collection sources

- Public DNS Info: https://public-dns.info/nameservers.csv
- Trickest Resolvers: https://raw.githubusercontent.com/trickest/resolvers/main/resolvers.txt
- Public DNS Directory — Iran: https://raw.githubusercontent.com/trybyteful/public-dns-directory/main/resolvers/by-country/ir.txt
- Public DNS Directory — global: https://raw.githubusercontent.com/trybyteful/public-dns-directory/main/resolvers/global/all.txt

These sources provide candidates only. Entries may be stale, duplicated, unreachable, or unsuitable for a particular network. DNS SniperX tests candidates on the phone's active network before marking transport results as successful. Source IDs are retained when duplicate resolver endpoints are merged. Source URL, retrieval time, candidate count, and per-source errors are recorded in source-collection history; they are not resolver ownership or trust attestations.

## Scanner behavior and result meanings

- UDP and TCP are tested independently with bounded timeouts and concurrency.
- The baseline query is `example.com A`; legacy DNS fallback is attempted when a server rejects EDNS with the supported error response codes.
- EDNS diagnostics compare DNSSEC-sized UDP responses advertised at 1232 and 4096 bytes.
- A random name under `.invalid` should not resolve. Unexpected answers are recorded as an anomaly for further investigation, not proof of malicious interception.
- DoH and DoT are separate transport diagnostics with their own histories; a successful DoH/DoT check does not prove DNSTT or Slipstream compatibility.
- Scan results are saved as each resolver finishes. The scheduler uses batches of up to 10 and reduces concurrency under moderate/severe thermal conditions, low battery, or power-saver mode; critical thermal state pauses scanning.
- `PASS`: a valid DNS response was received.
- `DNS_ERROR`: the resolver returned a DNS error response.
- `NO_ANSWER`: a valid response contained no answer.
- `TRUNCATED`: the UDP response was marked truncated.
- `TIMEOUT`: no response arrived before timeout.
- `FAIL`: a network or protocol error occurred.

A DNS `PASS` confirms DNS response behavior only. It does not prove that a blocked website is reachable, that censorship is bypassed, or that DNSTT/Slipstream can connect.

## Network DNS labels

The app can read DNS servers exposed by Android for the active network. The user may optionally attach a provider label such as Irancell, MCI, Rightel, TCI, Shatel, Asiatech, Sabanet, or Pars Online. This is a manually selected label, not automatic ISP detection or proof of IP ownership. On a VPN connection, Android may expose the VPN interface's DNS servers.

## Import and export formats

The parser accepts one endpoint per line, including:

- `1.1.1.1`
- `8.8.8.8:5353`
- `resolver.example`
- `[2001:db8::1]:53`
- An unbracketed IPv6 address without an explicit port, such as `2001:4860:4860::8888`

Port 53 is assumed when omitted. CSV-like sources with an address in the first column are also supported. Import performs syntax validation only; it does not test connectivity.

General TXT export provides resolver endpoints (IP or IP:port, with bracketed IPv6 when needed). The dedicated SlipNet scanner TXT export follows the scanner format: unique working IPv4 addresses on port 53, one per line. SlipNet profile resolver input has different constraints and is not the same export format.

## Tunnel implementation status

The current tunnel-related code stores and validates profile metadata for DNSTT and Slipstream, protects secret material with an Android Keystore-backed AES-GCM vault, and tests lifecycle state transitions. It does **not** yet contain the native tunnel clients, Android `VpnService` routing, secure end-to-end provisioning, or a completed connection flow. A profile being saved or validated must never be presented as a working tunnel.

## Privacy and network use

The app makes network requests only when the user starts collection, DNS scanning, or DoH/DoT diagnostics. Collection downloads the selected public lists from reviewed source hosts. DNS scans send test queries to the resolver endpoints being checked; DoH/DoT checks contact their configured diagnostic providers. Android's active network type and exposed DNS servers are read to label results. Resolver data, histories, and diagnostic logs are stored in the app's local database. There is no automatic upload of the database or logs, and no background resolver collection. TXT exports are user-initiated; review diagnostic exports before sharing them.

## Not implemented yet

- Configurable source adapters (while preserving the HTTPS host and redirect policy).
- Complete real-device tests on Wi-Fi and mobile networks, including supported Android API levels and Keystore behavior.
- Native DNSTT and Slipstream client adapters, secure configuration provisioning, and Android `VpnService` lifecycle/routing.
- Controlled end-to-end tunnel verification using a valid test server and required credentials/certificates.
- Physical-device testing of exported TXT files with SlipNet.
- Signed release builds and release notes.
