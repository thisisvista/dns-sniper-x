# DNSTT mobile engine: source and build provenance

## Selected source

- Project: [dnstt_xyz_app](https://github.com/dnstt-xyz/dnstt_xyz_app)
- Pinned source commit: `8155cde0353433646aceacca1414913fc9a304a7`
- Go module path: `www.bamsoftware.com/git/dnstt.git`
- Mobile binding: `go_src/mobile/mobile.go`
- Source license file: `go_src/COPYING` (CC0 1.0 Universal)
- Go toolchain requirement in the pinned `go.mod`: Go 1.24
- gomobile version: `v0.0.0-20260112195712-5b9ecdfb8721`

This is an independently maintained DNSTT mobile binding and source snapshot. It is **not** the missing `anonvector/dnstt-mobile` submodule and is **not** asserted to be byte-for-byte or behaviorally identical to SlipNet v2.5.6. The original SlipNet submodule pointer remains recorded in `SLIPNET_V2_5_6_APK_BINARY_AUDIT.md`.

## Build

Run:

```bash
scripts/build-dnstt-android-aar.sh
```

The script downloads the immutable source archive, verifies Go module checksums, installs the pinned gomobile tool, and builds Android ARM64, ARMv7 and x86_64 bindings for API 26+. The generated AAR is placed under `app/build/dnstt/` and is intentionally not committed as an opaque binary.

## Integration status

- [x] Select and pin a retrievable source snapshot.
- [x] Add a reproducible Android AAR build script.
- [ ] Audit transitive dependency licenses for redistribution.
- [ ] Inspect generated Java API and write the Kotlin lifecycle adapter.
- [ ] Implement isolated resolver socket routing for Android VPN coexistence.
- [ ] Add controlled-server handshake and real application-traffic tests.
- [ ] Connect verified results to the resolver/network evidence store.

Do not mark an E2E result as successful until the tunnel has carried an application-level request through a controlled DNSTT server. A successful AAR build alone proves only that the native binding compiles.
