# DNS SniperX

**Collect, test, and verify DNS resolvers on Android.**

[![Download APK](https://img.shields.io/badge/Download-APK-6D5CE8?style=for-the-badge&logo=android&logoColor=white)](https://github.com/thisisvista/dns-sniper-x/releases/latest)

[Latest release](https://github.com/thisisvista/dns-sniper-x/releases/latest) · [All releases](https://github.com/thisisvista/dns-sniper-x/releases) · [Report an issue](https://github.com/thisisvista/dns-sniper-x/issues)

## What it does

- Collects DNS resolver candidates from selected public sources.
- Imports resolver lists from TXT files.
- Tests DNS over UDP and TCP.
- Runs extended end-to-end checks where configured.
- Exports results for use with compatible tools such as SlipNet.
- Keeps scan history and diagnostic logs locally on your device.

## Download and install

1. Open the [latest release](https://github.com/thisisvista/dns-sniper-x/releases/latest).
2. Download **app-release.apk**.
3. Open the downloaded file on your Android device and follow the installation prompt.

GitHub Releases is the official download location. The release APK is intended to support multiple Android CPU architectures in one package.

## Important

A successful UDP or TCP DNS test only confirms that the resolver answered the test query. It does not guarantee access to blocked websites or a working tunnel. Only results that pass the app's full end-to-end verification should be treated as verified tunnel-compatible results.

## Project status

DNS SniperX is under active development. Some advanced tunnel and network capabilities may still be evolving. Check the release notes for changes in each version.

## Privacy

Resolver lists, scan results, history, and diagnostic logs are stored locally in the app. Network requests are made when you start collection or a test. The app does not automatically upload your local database or logs.

## Feedback

Found a problem or have a suggestion? [Open an issue](https://github.com/thisisvista/dns-sniper-x/issues).
