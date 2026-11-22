#!/usr/bin/env bash
set -euo pipefail

# Reproducible DNSTT Android AAR build.
# Source is pinned to an immutable commit; this is an independent DNSTT
# mobile binding, not a claim of byte-for-byte SlipNet engine equivalence.
readonly SOURCE_REPO="https://github.com/dnstt-xyz/dnstt_xyz_app"
readonly SOURCE_COMMIT="8155cde0353433646aceacca1414913fc9a304a7"
readonly GOMOBILE_VERSION="v0.0.0-20260112195712-5b9ecdfb8721"
readonly ANDROID_API="${ANDROID_API:-26}"
readonly TARGETS="${ANDROID_TARGETS:-android/arm64,android/arm,android/amd64}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if ! command -v go >/dev/null 2>&1; then
  echo "Go is required (Go 1.24+)." >&2
  exit 1
fi
if ! command -v curl >/dev/null 2>&1 || ! command -v tar >/dev/null 2>&1; then
  echo "curl and tar are required." >&2
  exit 1
fi

ARCHIVE="$WORK/source.tar.gz"
curl --fail --location --silent --show-error \
  "$SOURCE_REPO/archive/$SOURCE_COMMIT.tar.gz" -o "$ARCHIVE"
mkdir -p "$WORK/dnstt"
tar -xzf "$ARCHIVE" --strip-components=2 -C "$WORK/dnstt"

if [[ ! -f "$WORK/dnstt/mobile/mobile.go" || ! -f "$WORK/dnstt/COPYING" ]]; then
  echo "Pinned source archive is incomplete or has an unexpected layout." >&2
  exit 1
fi

cd "$WORK/dnstt"
go mod download
go mod verify

export PATH="$(go env GOPATH)/bin:$PATH"
go install "golang.org/x/mobile/cmd/gomobile@$GOMOBILE_VERSION"
gomobile init

OUT="$ROOT/app/build/dnstt/dnstt.aar"
mkdir -p "$(dirname "$OUT")"
GOFLAGS="-trimpath" gomobile bind -v \
  -ldflags="-s -w" \
  -androidapi "$ANDROID_API" \
  -target="$TARGETS" \
  -o "$OUT" ./mobile

test -s "$OUT"
echo "DNSTT AAR built: $OUT"
sha256sum "$OUT"
