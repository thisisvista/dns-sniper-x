# DNS SniperX

An independent Android app for collecting, storing, testing and exporting DNS resolver candidates.

## Current status

- Android project foundation builds in GitHub Actions.
- Local Room database and manual DNS list import are implemented.
- Public-list collection downloads candidate endpoints from Public DNS Info and Trickest Resolvers over HTTPS.
- DNS probes send a standard `example.com A` query over UDP and TCP, validate the DNS response header and transaction ID, measure response time, and store separate transport results.
- Scanning is limited to 100 saved entries per run and 8 concurrent resolvers with short timeouts.
- Debug APK is uploaded as a GitHub Actions artifact after a successful build.
- **DNS reachability is not DNSTT/Slipstream compatibility.** Tunnel end-to-end checks and SlipNet export are not implemented yet.

## Public collection sources

- Public DNS Info: https://public-dns.info/nameservers.csv
- Trickest Resolvers: https://github.com/trickest/resolvers

These are candidate lists only. Their contents may include stale or unreachable addresses. DNS SniperX must test candidates on the user's current network before ranking them as usable.

## Scanner result meanings

- `PASS`: a matching DNS response was received over the selected transport.
- `TIMEOUT`: no response arrived before the timeout.
- `FAIL`: connection or response validation failed.
- UDP and TCP outcomes are stored separately.

The scanner tests the network currently used by Android. A passing DNS query does not prove that a tunnel can be established.

## Import format

Paste one endpoint per line in the app:

- `1.1.1.1`
- `8.8.8.8:5353`
- `resolver.example`
- `[2001:db8::1]:53`

Port 53 is assumed when omitted. Syntax validation does not test connectivity.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/ROADMAP.md](docs/ROADMAP.md).
