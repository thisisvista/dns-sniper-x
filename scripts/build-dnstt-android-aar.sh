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

# Adapt the upstream callback/API for gomobile and harden failed-start cleanup.
python3 - <<'PY'
from pathlib import Path
p = Path("mobile/mobile.go")
s = p.read_text()
replacements = [
    ("// ProtectSocketFunc is a callback to protect a socket from VPN routing\ntype ProtectSocketFunc func(fd int) bool",
     "// SocketProtector is implemented by Android to exclude the resolver socket from VPN routing.\ntype SocketProtector interface {\n\tProtect(fd int) bool\n}"),
    ("\ttunFd         int\n\tprotectSocket ProtectSocketFunc",
     "\tprotectSocket SocketProtector"),
    ("\t\ttunFd:      -1,\n", ""),
    ("// SetTunFd sets the TUN file descriptor for routing traffic\nfunc (c *DnsttClient) SetTunFd(fd int) {\n\tc.tunFd = fd\n}\n\n", ""),
    ("// SetProtectSocket sets a callback to protect sockets from VPN routing\nfunc (c *DnsttClient) SetProtectSocket(protectFunc ProtectSocketFunc) {\n\tc.mu.Lock()\n\tdefer c.mu.Unlock()\n\tc.protectSocket = protectFunc\n}",
     "// SetSocketProtector installs the Android socket-protection callback before Start.\nfunc (c *DnsttClient) SetSocketProtector(protector SocketProtector) {\n\tc.mu.Lock()\n\tdefer c.mu.Unlock()\n\tc.protectSocket = protector\n}"),
    ("if !c.protectSocket(int(fd)) {", "if !c.protectSocket.Protect(int(fd)) {"),
    ("// DialTunnel creates a connection through the tunnel to the specified address\nfunc (c *DnsttClient) DialTunnel(address string) (net.Conn, error) {",
     "// GetListenPort returns the actual TCP port selected by the local listener.\nfunc (c *DnsttClient) GetListenPort() int {\n\tc.mu.Lock()\n\tdefer c.mu.Unlock()\n\tif c.listener == nil {\n\t\treturn 0\n\t}\n\taddr, ok := c.listener.Addr().(*net.TCPAddr)\n\tif !ok {\n\t\treturn 0\n\t}\n\treturn addr.Port\n}\n\n// DialTunnel creates a connection through the tunnel to the specified address\nfunc (c *DnsttClient) DialTunnel(address string) (net.Conn, error) {"),
    # Always cancel background packet loops and close partially initialized resources on Start errors.
    ("\tc.ctx, c.cancel = context.WithCancel(context.Background())\n",
     "\tc.ctx, c.cancel = context.WithCancel(context.Background())\n\tstarted := false\n\tdefer func() {\n\t\tif started {\n\t\t\treturn\n\t\t}\n\t\tif c.cancel != nil { c.cancel() }\n\t\tif c.listener != nil { _ = c.listener.Close(); c.listener = nil }\n\t\tif c.sess != nil { _ = c.sess.Close(); c.sess = nil }\n\t\tif c.conn != nil { _ = c.conn.Close(); c.conn = nil }\n\t}()\n"),
    ("\tdnsConn := newDNSPacketConn(pconn, remoteAddr, domain)",
     "\tdnsConn := newDNSPacketConn(c.ctx, pconn, remoteAddr, domain)"),
    ("\tc.running = true\n\tlog.Printf(\"dnstt client started, listening on %s\", c.listenAddr)",
     "\tc.running = true\n\tstarted = true\n\tlog.Printf(\"dnstt client started, listening on %s\", c.listenAddr)"),
    ("func newDNSPacketConn(transport net.PacketConn, addr net.Addr, domain dns.Name) *dnsPacketConn {",
     "func newDNSPacketConn(ctx context.Context, transport net.PacketConn, addr net.Addr, domain dns.Name) *dnsPacketConn {"),
    ("\tgo c.sendLoop(transport, addr)",
     "\tgo c.sendLoop(ctx, transport, addr)"),
    ("func (c *dnsPacketConn) sendLoop(transport net.PacketConn, addr net.Addr) {",
     "func (c *dnsPacketConn) sendLoop(ctx context.Context, transport net.PacketConn, addr net.Addr) {"),
    ("\t\t\tcase <-pollTimer.C:\n\t\t\t\tpollTimerExpired = true",
     "\t\t\tcase <-pollTimer.C:\n\t\t\t\tpollTimerExpired = true\n\t\t\tcase <-ctx.Done():\n\t\t\t\tpollTimer.Stop()\n\t\t\t\treturn"),
]
for old, new in replacements:
    if old not in s:
        raise SystemExit("Expected upstream source fragment not found; refusing an unsafe partial patch: " + old[:100])
    s = s.replace(old, new, 1)
p.write_text(s)
PY

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

VERIFY="$WORK/aar-verify"
mkdir -p "$VERIFY"
unzip -q "$OUT" classes.jar -d "$VERIFY"
API="$(javap -classpath "$VERIFY/classes.jar" -public mobile.DnsttClient mobile.Mobile mobile.SocketProtector)"
printf '%s\n' "$API"
grep -q 'setSocketProtector' <<<"$API"
grep -q 'getListenPort()' <<<"$API"
grep -q 'protect(long)' <<<"$API"

test -s "$OUT"
echo "DNSTT AAR built and Java API verified: $OUT"
sha256sum "$OUT"
