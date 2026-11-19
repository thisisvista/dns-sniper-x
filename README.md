# DNS SniperX

An independent Android app for discovering, storing, testing, and exporting DNS resolver candidates.

## Current implementation

- Android app with a local Room database (schema version 4) for saved resolver entries.
- Public resolver collection over HTTPS from reviewed sources, including a regional Iran list.
- When the same address and port appear in multiple sources, entries are merged and all source IDs are retained.
- Manual paste and TXT-file import. Large TXT files are read in chunks of up to 500 lines; the user selects the file only once.
- DNS scanning over UDP and TCP, with up to 8 concurrent resolvers and batches of up to 500 entries.
- Scan progress, pause/resume, immediate persistence of completed results, and retry for unsuccessful or warning results.
- Separate UDP and TCP status and latency, last-tested time, search, filtering, and incremental list display.\n- Persistent scan, DoH, DoT, and source-collection histories are stored locally with bounded retention.
- UDP/TCP scans include EDNS payload and randomized reserved-domain (`.invalid`) diagnostics. An anomaly warning is a signal for further investigation, not proof of malicious interception.
- Separate DoH (RFC 8484) and DoT (TLS on port 853) connectivity diagnostics are available; they do not verify tunnel compatibility.
- General resolver export retains non-default ports and formats IPv6 endpoints with brackets. A dedicated SlipNet scanner export emits only unique, working IPv4 resolvers on port 53, one IP per line.
- Debug APK is built and uploaded as a GitHub Actions artifact after successful CI.

## Public collection sources

- Public DNS Info: https://public-dns.info/nameservers.csv
- Trickest Resolvers: https://raw.githubusercontent.com/trickest/resolvers/main/resolvers.txt
- Public DNS Directory — Iran: https://raw.githubusercontent.com/trybyteful/public-dns-directory/main/resolvers/by-country/ir.txt
- Public DNS Directory — global: https://raw.githubusercontent.com/trybyteful/public-dns-directory/main/resolvers/global/all.txt

These sources provide candidates only. Entries may be stale, duplicated, or unreachable. DNS SniperX tests candidates on the phone's active network before treating them as working. Source IDs are retained when duplicates are merged; source URL, retrieval timestamp, and license/terms metadata are not yet stored per resolver.

## Scanner behavior and result meanings

- The base probe sends a standard DNS query for `example.com A` over UDP and TCP, with a legacy retry when a server rejects EDNS.
- UDP and TCP outcomes are recorded separately, including latency and last-tested time.
- Additional diagnostics compare DNSSEC-sized EDNS UDP responses and query random names below the reserved `.invalid` TLD to identify possible wildcard or intercepted responses.
- DoH and DoT checks are separate transport diagnostics with their own histories.
- Scanning runs automatically in batches of up to 500 entries, with 8 concurrent resolvers. Completed results are saved as the scan progresses.
- `PASS`: a valid DNS response was received.
- `DNS_ERROR`: the resolver returned a DNS error response.
- `NO_ANSWER`: a valid response contained no answer.
- `TRUNCATED`: the UDP response was marked truncated.
- `TIMEOUT`: no response arrived before the timeout.
- `FAIL`: a network or protocol error occurred.

A DNS `PASS` confirms DNS response behavior only. It does not prove that a blocked website is reachable or that DNSTT, Slipstream, or another tunnel can connect.

## Network DNS labels

The app can read DNS servers exposed by Android for the active network. The user may optionally attach a provider label such as Irancell, MCI, Rightel, TCI, Shatel, Asiatech, Sabanet, or Pars Online. This is a user-selected label, not automatic ISP detection or proof of IP ownership. On a VPN connection, Android may expose the VPN interface's DNS servers.

## Import and export formats

The parser accepts one endpoint per line, including:

- `1.1.1.1`
- `8.8.8.8:5353`
- `resolver.example`
- `[2001:db8::1]:53`
- An unbracketed IPv6 address without an explicit port, such as `2001:4860:4860::8888`

Port 53 is assumed when omitted. CSV-like sources with an address in the first column are also supported. Import performs syntax validation only; it does not test connectivity.

General TXT export provides resolver endpoints (IP or IP:port, with bracketed IPv6 when needed). A separate SlipNet scanner TXT export follows the verified scanner format: unique working IPv4 addresses on port 53, one per line. SlipNet profile resolver input has different constraints and is not the same export format.

## Not implemented yet

- CIDR-range expansion and controlled resolver discovery.
- Full DNSTT and Slipstream end-to-end tunnel verification with a valid test server and credentials/certificates.
- Real-device instrumentation and network tests on Wi-Fi and mobile networks.
- Signed release builds and release-readiness disclosures.
- Verified ISP ownership classification; provider labels remain user-selected descriptions.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/ROADMAP.md](docs/ROADMAP.md).
