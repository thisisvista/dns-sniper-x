# DNS SniperX

**An independent Android app for collecting, testing, organizing, and exporting DNS resolvers.**

DNS SniperX is being built to help users discover DNS resolvers, test them on the phone's current network, keep a local resolver library, and export suitable results for compatible tunneling clients such as SlipNet.

> **Development status:** early foundation. The Android project structure and starter screen are being prepared. DNS collection, scanning, tunnel compatibility checks, and export are not implemented yet.

## Goals

- Collect resolver candidates from documented public sources and user imports.
- Keep a local database so previously collected candidates remain available offline.
- Test candidates over supported transports and record latency and network context.
- Separate basic DNS reachability from tunnel compatibility and real end-to-end tunnel tests.
- Export selected results in a documented format that can later be adapted to SlipNet.

## Important limitations

- An offline database can be browsed without internet, but a fresh network test requires a working route to the resolver.
- A successful DNS query or an open port does **not** prove that DNSTT or Slipstream will work.
- End-to-end tunnel status can only be confirmed against a valid test server and the required client configuration.
- Results can differ between mobile operators, Wi-Fi networks, locations, and time.

## Technology

- Android app: Kotlin, Jetpack Compose, Material 3
- Local storage: Room / SQLite (planned)
- Scanner core: modular design; Go-based components from other projects will be evaluated before any reuse
- Minimum Android version: Android 8.0 (API 26), subject to change after device testing

## Project structure

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the planned modules and technical boundaries, and [docs/ROADMAP.md](docs/ROADMAP.md) for implementation stages.

## Build

Open this repository in a recent Android Studio release with Android SDK 36 and JDK 17 installed. The Gradle wrapper will be added as part of the build setup. Until then, use a locally installed compatible Gradle 8.13 distribution to run:

```bash
gradle assembleDebug
```

The generated debug APK, when the build succeeds, is located at `app/build/outputs/apk/debug/app-debug.apk`.

## License

The project is currently marked as **All rights reserved** until a project license is deliberately selected. Third-party components, if adopted, will retain their own license notices. No Findns source code has been copied into this initial scaffold.
