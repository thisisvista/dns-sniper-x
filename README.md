# DNS SniperX

An independent Android app for collecting, storing, testing and exporting DNS resolver candidates.

## Current status

- Android project foundation builds in GitHub Actions.
- Local Room database and manual DNS list import are implemented.
- Public-list collection downloads candidate endpoints from Public DNS Info and Trickest Resolvers over HTTPS.
- Collection runs off the UI thread, applies connection/read timeouts and per-source size limits, records source provenance, and continues if one source is unavailable.
- Imported entries are syntax-checked and deduplicated by address and port.
- **No network scanner, DNSTT/Slipstream end-to-end test, or SlipNet export is implemented yet.** A saved DNS entry is not evidence that it works.

## Public collection sources

- Public DNS Info: https://public-dns.info/nameservers.csv
- Trickest Resolvers: https://github.com/trickest/resolvers

These are candidate lists only. Their contents may include stale or unreachable addresses. DNS SniperX must test candidates on the user's current network before ranking them as usable.

## Import format

Paste one endpoint per line in the app:

- `1.1.1.1`
- `8.8.8.8:5353`
- `resolver.example`
- `[2001:db8::1]:53`

Port 53 is assumed when omitted. Syntax validation does not test connectivity.

## Product principles

- Keep a local library that can be browsed offline.
- Preserve source information and test history.
- Test transports separately (UDP, TCP, DoH, DoT).
- Distinguish DNS reachability, tunnel compatibility and real end-to-end tunnel success.
- Use public or user-provided sources responsibly and apply scan limits.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/ROADMAP.md](docs/ROADMAP.md).
