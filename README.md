# DNS SniperX

An independent Android app for collecting, storing, testing and exporting DNS resolver candidates.

## Current status

- Android project foundation builds successfully in GitHub Actions.
- Local Room database and manual DNS list import are implemented in the current development branch.
- Imported entries are syntax-checked and deduplicated by address and port.
- **No network scanner, DNSTT/Slipstream end-to-end test, or SlipNet export is implemented yet.** A saved DNS entry is not evidence that it works.

## Import format

Paste one endpoint per line in the app:

- `1.1.1.1`
- `8.8.8.8:5353`
- `resolver.example`
- `[2001:db8::1]:53`

Port 53 is assumed when omitted. The current importer validates endpoint syntax; it does not resolve hostnames or test connectivity.

## Build

The GitHub Actions workflow builds the Android debug APK using JDK 17, Android SDK 36 and Gradle 8.13.

## Product principles

- Keep a local library that can be browsed offline.
- Preserve source information and test history.
- Test transports separately (UDP, TCP, DoH, DoT).
- Distinguish DNS reachability, tunnel compatibility and real end-to-end tunnel success.
- Use public or user-provided sources responsibly and apply scan limits.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/ROADMAP.md](docs/ROADMAP.md).
