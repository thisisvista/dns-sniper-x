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
    ("\t\"sync\"\n\t\"time\"", "\t\"sync\"\n\t\"sync/atomic\"\n\t\"time\""),
    ("\tmu            sync.Mutex\n\trunning", "\tmu            sync.Mutex\n\tstartConn     atomic.Pointer[net.UDPConn]\n\tstartKcp      atomic.Pointer[kcp.UDPSession]\n\tstartState    atomic.Uint32\n\trunning"),
    ("\tpconn, err := net.ListenUDP(\"udp4\", localAddr)\n\tif err != nil {\n\t\treturn fmt.Errorf(\"failed to create UDP socket: %v\", err)\n\t}",
     "\tpconn, err := net.ListenUDP(\"udp4\", localAddr)\n\tif err != nil {\n\t\treturn fmt.Errorf(\"failed to create UDP socket: %v\", err)\n\t}\n\t// Publish the in-progress transport before the blocking Noise handshake.\n\tc.startConn.Store(pconn)\n\tdefer c.startConn.CompareAndSwap(pconn, nil)"),
    ("// Stop stops the client\nfunc (c *DnsttClient) Stop() {",
     "// CancelStart interrupts Start without waiting for the lifecycle mutex.\nfunc (c *DnsttClient) CancelStart() {\n\tfor {\n\t\tstate := c.startState.Load()\n\t\tif state >= 2 { break }\n\t\tif c.startState.CompareAndSwap(state, 2) { break }\n\t}\n\tif conn := c.startKcp.Load(); conn != nil { _ = conn.Close() }\n\tif conn := c.startConn.Load(); conn != nil { _ = conn.Close() }\n\tif c.startState.Load() == 3 { c.Stop() }\n}\n\n// Stop stops the client\nfunc (c *DnsttClient) Stop() {"),
    ("// Start starts the SOCKS5 proxy\nfunc (c *DnsttClient) Start() error {\n\tc.mu.Lock()\n\tdefer c.mu.Unlock()\n\n\tif c.running {\n\t\treturn nil\n\t}",
     "// Start starts the SOCKS5 proxy\nfunc (c *DnsttClient) Start() error {\n\tc.mu.Lock()\n\tdefer c.mu.Unlock()\n\n\tif c.running {\n\t\treturn nil\n\t}\n\tif !c.startState.CompareAndSwap(0, 1) {\n\t\treturn fmt.Errorf(\"DNSTT start was cancelled\")\n\t}\n\tlifecycleStarted := false\n\tdefer func() { if !lifecycleStarted { c.startState.CompareAndSwap(1, 0) } }()"),
    ("\tkcpConn, err := kcp.NewConn2(remoteAddr, nil, 0, 0, dnsConn)\n\tif err != nil {\n\t\tpconn.Close()\n\t\treturn fmt.Errorf(\"opening KCP conn: %v\", err)\n\t}",
     "\tkcpConn, err := kcp.NewConn2(remoteAddr, nil, 0, 0, dnsConn)\n\tif err != nil {\n\t\tpconn.Close()\n\t\treturn fmt.Errorf(\"opening KCP conn: %v\", err)\n\t}\n\tc.startKcp.Store(kcpConn)\n\tdefer c.startKcp.CompareAndSwap(kcpConn, nil)\n\tif c.startState.Load() == 2 { _ = kcpConn.Close(); return fmt.Errorf(\"DNSTT start was cancelled\") }"),
    # Always cancel background packet loops and close partially initialized resources on Start errors.
    ("\tc.ctx, c.cancel = context.WithCancel(context.Background())\n",
     "\tc.ctx, c.cancel = context.WithCancel(context.Background())\n\tstarted := false\n\tdefer func() {\n\t\tif started {\n\t\t\treturn\n\t\t}\n\t\tif c.cancel != nil { c.cancel() }\n\t\tif c.listener != nil { _ = c.listener.Close(); c.listener = nil }\n\t\tif c.sess != nil { _ = c.sess.Close(); c.sess = nil }\n\t\tif c.conn != nil { _ = c.conn.Close(); c.conn = nil }\n\t}()\n"),
    ("\tdnsConn := newDNSPacketConn(pconn, remoteAddr, domain)",
     "\tdnsConn := newDNSPacketConn(c.ctx, pconn, remoteAddr, domain)"),
    ("\tc.running = true\n\tlog.Printf(\"dnstt client started, listening on %s\", c.listenAddr)",
     "\tif !c.startState.CompareAndSwap(1, 3) { return fmt.Errorf(\"DNSTT start was cancelled\") }\n\tlifecycleStarted = true\n\tc.running = true\n\tstarted = true\n\tlog.Printf(\"dnstt client started, listening on %s\", c.listenAddr)"),
    ("func newDNSPacketConn(transport net.PacketConn, addr net.Addr, domain dns.Name) *dnsPacketConn {",
     "func newDNSPacketConn(ctx context.Context, transport net.PacketConn, addr net.Addr, domain dns.Name) *dnsPacketConn {"),
    ("\tgo c.sendLoop(transport, addr)",
     "\tgo c.sendLoop(ctx, transport, addr)"),
    ("func (c *dnsPacketConn) sendLoop(transport net.PacketConn, addr net.Addr) {",
     "func (c *dnsPacketConn) sendLoop(ctx context.Context, transport net.PacketConn, addr net.Addr) {"),
    ("\t\t\tcase <-pollTimer.C:\n\t\t\t\tpollTimerExpired = true",
     "\t\t\tcase <-pollTimer.C:\n\t\t\t\tpollTimerExpired = true\n\t\t\tcase <-ctx.Done():\n\t\t\t\tpollTimer.Stop()\n\t\t\t\treturn"),

    # Prioritize cancellation even while outgoing packets are continuously queued.
    ("\tfor {\n\t\tvar p []byte\n\t\toutgoing := c.QueuePacketConn.OutgoingQueue(addr)",
     "\tfor {\n\t\tselect { case <-ctx.Done(): pollTimer.Stop(); return; default: }\n\t\tvar p []byte\n\t\toutgoing := c.QueuePacketConn.OutgoingQueue(addr)"),
    ("\t\t\tif !pollTimer.Stop() {\n\t\t\t\t<-pollTimer.C\n\t\t\t}",
     "\t\t\tif !pollTimer.Stop() {\n\t\t\t\tselect { case <-pollTimer.C: default: }\n\t\t\t}"),
    ("\t\tpollTimer.Reset(pollDelay)\n\n\t\terr := c.send(transport, p, addr)",
     "\t\tpollTimer.Reset(pollDelay)\n\t\tselect { case <-ctx.Done(): pollTimer.Stop(); return; default: }\n\n\t\terr := c.send(transport, p, addr)"),
]
for old, new in replacements:
    if old not in s:
        raise SystemExit("Expected upstream source fragment not found; refusing an unsafe partial patch: " + old[:100])
    s = s.replace(old, new, 1)
p.write_text(s)

# Add focused tests for the patched sender-loop cancellation behavior.
Path("mobile/mobile_lifecycle_test.go").write_text(r'''package mobile

import (
	"context"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"www.bamsoftware.com/git/dnstt.git/dns"
	"www.bamsoftware.com/git/dnstt.git/turbotunnel"
)

type lifecycleTestPacketConn struct {
	writes atomic.Int64
}

func (c *lifecycleTestPacketConn) ReadFrom([]byte) (int, net.Addr, error) {
	return 0, nil, net.ErrClosed
}
func (c *lifecycleTestPacketConn) WriteTo(p []byte, _ net.Addr) (int, error) {
	c.writes.Add(1)
	return len(p), nil
}
func (c *lifecycleTestPacketConn) Close() error                       { return nil }
func (c *lifecycleTestPacketConn) LocalAddr() net.Addr                 { return &net.UDPAddr{} }
func (c *lifecycleTestPacketConn) SetDeadline(time.Time) error         { return nil }
func (c *lifecycleTestPacketConn) SetReadDeadline(time.Time) error     { return nil }
func (c *lifecycleTestPacketConn) SetWriteDeadline(time.Time) error    { return nil }

func newLifecycleTestDNSPacketConn(t *testing.T) (*dnsPacketConn, net.Addr) {
	t.Helper()
	domain, err := dns.ParseName("t.example.com")
	if err != nil {
		t.Fatal(err)
	}
	addr := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 53}
	queue := turbotunnel.NewQueuePacketConn(&net.UDPAddr{}, 0)
	return &dnsPacketConn{
		clientID:        turbotunnel.NewClientID(),
		domain:          domain,
		pollChan:        make(chan struct{}, pollLimit),
		QueuePacketConn: queue,
	}, addr
}

func awaitLoopExit(t *testing.T, done <-chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("sendLoop did not exit promptly after cancellation")
	}
}

func TestSendLoopCancellationIsNotStarvedByContinuouslyQueuedPackets(t *testing.T) {
	packetConn, addr := newLifecycleTestDNSPacketConn(t)
	transport := &lifecycleTestPacketConn{}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		packetConn.sendLoop(ctx, transport, addr)
		close(done)
	}()

	stopWriter := make(chan struct{})
	writerDone := make(chan struct{})
	go func() {
		defer close(writerDone)
		packet := []byte{1, 2, 3, 4}
		for {
			select {
			case <-stopWriter:
				return
			default:
				_, _ = packetConn.QueuePacketConn.WriteTo(packet, addr)
			}
		}
	}()

	deadline := time.Now().Add(2 * time.Second)
	for transport.writes.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if transport.writes.Load() == 0 {
		close(stopWriter)
		<-writerDone
		cancel()
		awaitLoopExit(t, done)
		t.Fatal("sendLoop did not process any queued packets")
	}

	cancel()
	awaitLoopExit(t, done)
	close(stopWriter)
	select {
	case <-writerDone:
	case <-time.After(time.Second):
		t.Fatal("packet producer did not stop")
	}
}

func TestSendLoopCancellationInterruptsIdlePollTimerWait(t *testing.T) {
	packetConn, addr := newLifecycleTestDNSPacketConn(t)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		packetConn.sendLoop(ctx, &lifecycleTestPacketConn{}, addr)
		close(done)
	}()

	time.Sleep(10 * time.Millisecond)
	cancel()
	awaitLoopExit(t, done)
}
''')
Path("mobile/mobile_cancel_test.go").write_text(r'''package mobile

import (
	"net"
	"testing"
	"time"
)

func TestCancelStartClosesPublishedStartupTransport(t *testing.T) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil { t.Fatal(err) }
	client := &DnsttClient{}
	client.startConn.Store(conn)

	readDone := make(chan error, 1)
	go func() {
		buf := make([]byte, 32)
		_, _, readErr := conn.ReadFromUDP(buf)
		readDone <- readErr
	}()

	client.CancelStart()
	select {
	case err := <-readDone:
		if err == nil { t.Fatal("expected startup UDP read to be interrupted") }
	case <-time.After(time.Second):
		t.Fatal("CancelStart did not interrupt the UDP socket read")
	}
}
''')
Path("mobile/mobile_start_cancel_test.go").write_text(r'''package mobile

import (
	"net"
	"testing"
	"time"
)

func TestCancelStartInterruptsNoiseHandshakeWait(t *testing.T) {
	resolver, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil { t.Fatal(err) }
	defer resolver.Close()
	client, err := NewClient(resolver.LocalAddr().String(), "t.example.com", "0000000000000000000000000000000000000000000000000000000000000000", "127.0.0.1:0")
	if err != nil { t.Fatal(err) }
	startDone := make(chan error, 1)
	go func() { startDone <- client.Start() }()
	deadline := time.Now().Add(2 * time.Second)
	for client.startConn.Load() == nil && time.Now().Before(deadline) { time.Sleep(time.Millisecond) }
	if client.startConn.Load() == nil { t.Fatal("Start did not publish its UDP transport") }
	cancelAt := time.Now()
	client.CancelStart()
	select {
	case err := <-startDone:
		if err == nil { t.Fatal("expected cancelled Start to return an error") }
		if elapsed := time.Since(cancelAt); elapsed > time.Second { t.Fatalf("CancelStart took too long: %v", elapsed) }
	case <-time.After(time.Second):
		t.Fatal("CancelStart did not interrupt Start promptly")
	}
}

func TestCancelStartBeforeStartPreventsStartup(t *testing.T) {
	client, err := NewClient("127.0.0.1:53", "t.example.com", "0000000000000000000000000000000000000000000000000000000000000000", "127.0.0.1:0")
	if err != nil { t.Fatal(err) }
	client.CancelStart()
	if err := client.Start(); err == nil { t.Fatal("Start unexpectedly succeeded after cancellation") }
}
''')
PY

# Exercise the pinned upstream packages after the compatibility and lifecycle patches.
go test ./...

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
grep -q 'cancelStart()' <<<"$API"
grep -q 'protect(long)' <<<"$API"

test -s "$OUT"
echo "DNSTT AAR built and Java API verified: $OUT"
sha256sum "$OUT"
