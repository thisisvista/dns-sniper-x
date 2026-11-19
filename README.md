# DNS SniperX

An independent Android app for discovering, storing, testing, and exporting DNS resolver candidates.

## Current implementation

- Android app with a local Room database for saved resolver entries.
- Public resolver collection over HTTPS from the sources listed below, including a regional Iran list.
- Manual paste and TXT-file import. Large TXT files are read in chunks of up to 500 lines; the user selects the file only once.
- DNS scanning over UDP and TCP, with up to 8 concurrent resolvers and batches of up to 500 entries.
- Scan progress, pause/resume, immediate persistence of completed results, and retry for unsuccessful or warning results.
- Separate UDP and TCP status and latency, last-tested time, search, filtering, and incremental display of saved entries.
- NXDOMAIN and EDNS diagnostics are warnings/diagnostics, not proof of malicious interception or tunnel compatibility.
- Export of entries with at least one successful transport, sorted by the fastest successful UDP/TCP latency. Non-default ports are retained; IPv6 endpoints are bracketed when a port is included.
- Debug APK is built and uploaded as a GitHub Actions artifact after successful CI.

## Public collection sources

- Public DNS Info: https://public-dns.info/nameservers.csv
- Trickest Resolvers: https://raw.githubusercontent.com/trickest/resolvers/main/resolvers.txt
- Public DNS Directory — Iran: https://raw.githubusercontent.com/trybyteful/public-dns-directory/main/resolvers/by-country/ir.txt
- Public DNS Directory — global: https://raw.githubusercontent.com/trybyteful/public-dns-directory/main/resolvers/global/all.txt

These sources provide candidates only. Entries may be stale, duplicated, or unreachable. DNS SniperX tests candidates on the phone's active network before treating them as working.

## Scanner behavior and result meanings

- The current probe sends a standard DNS query for `example.com A` over UDP and TCP.
- UDP and TCP outcomes are recorded separately, including latency and last-tested time.
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

The TXT export currently provides resolver endpoints (IP or IP:port, with bracketed IPv6 when needed). **Compatibility with SlipNet's exact import parser has not yet been verified.**

## Not implemented yet

- DoH scanning.
- CIDR-range expansion and large-scale resolver discovery.
- Full DNSTT and Slipstream end-to-end tunnel verification.
- Verified ISP ownership classification.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/ROADMAP.md](docs/ROADMAP.md).
