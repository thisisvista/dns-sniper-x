# E2E engine scope for SlipNet compatibility

Date: 2026-11-22

## Product goal

DNS SniperX is a discovery, validation, and export tool for finding DNS resolver endpoints that can actually carry traffic through the tunnel engines used by SlipNet. It is not intended to replace SlipNet or become a full-device VPN client.

## Approved engine scope

Only these two tunnel engines are in the current implementation scope:

1. **DNSTT** — first implementation and compatibility baseline.
2. **VayDNS** — second supported engine, to be integrated and tested independently after the DNSTT path is verified.

Explicitly out of scope for now:
- NoizDNS
- Slipstream
- Soar and other optional tunnel engines
- A full-device Android VPN/TUN service

The architecture should keep engine-specific adapters isolated so additional engines can be added later without rewriting collection, resolver storage, scan history, or E2E history. This extensibility is an architectural provision, not a commitment to implement the excluded engines now.

## E2E acceptance requirements

A resolver must not be marked successful merely because:
- UDP or TCP port 53 responds;
- a DNS query returns an answer;
- a local tunnel process reports that it is running;
- a local SOCKS listener accepts a connection.

For each supported engine, a FULL E2E pass requires:
1. Start that engine with the selected resolver and a matching test profile.
2. Establish the remote tunnel to a controlled server.
3. Route a real application request through the tunnel.
4. Validate the expected HTTP response (including exact expected status where configured).
5. Stop the engine and clean up sockets reliably.

A resolver may pass DNSTT and fail VayDNS, or the reverse. Results must be stored separately by resolver, engine/profile, network, and test time. A successful result on one network must not be generalized to another network or treated as proof that the resolver is owned by that mobile/fixed operator.

## Delivery order

### Phase 1 — DNSTT
- Finish the pinned Android binding and build pipeline.
- Verify lifecycle, cancellation, timeouts, and socket cleanup.
- Run FULL E2E against a controlled DNSTT server and a known HTTP verification target.
- Only then allow DNSTT FULL_PASS records and SlipNet-compatible export.

### Phase 2 — VayDNS
- Inspect the exact VayDNS source revision and build recipe referenced by the APK-matching SlipNet v2.5.6 source.
- Pin source and dependency revisions; audit licenses and provenance.
- Build only the minimal Android binding/API surface needed by SniperX.
- Implement a separate VayDNS adapter and controlled-server FULL E2E tests.
- Do not reuse DNSTT settings or infer VayDNS compatibility from a DNSTT pass.
- Add VayDNS export only after its own end-to-end test passes.

### Deferred
NoizDNS, Slipstream, Soar, and full-device VPN/TUN integration remain deferred unless the product owner explicitly changes scope.

## Export contract

Export records must identify at least:
- resolver IP and port;
- engine name (DNSTT or VayDNS);
- engine-specific profile/configuration needed by SlipNet;
- E2E status and verification strength;
- last successful test time and elapsed time;
- network context on which it passed.

Do not export a generic "working DNS" label without identifying which engine was actually tested.

## Implemented export behavior

The E2E section provides two separate files:

- `dns-sniperx-slipnet-e2e.txt`: one IPv4 address per line, suitable for SlipNet's DNS-list import. It contains only resolver endpoints with a recorded `FULL_PASS`, `FULL` verification, and successful HTTP status (200–399). It does not include UDP/TCP-only candidates or handshake-only attempts.
- `dns-sniperx-slipnet-e2e-report.json`: a versioned evidence report containing the resolver endpoint, engine/profile identity, non-secret engine configuration (including the DNSTT public key), last successful test time, elapsed time, HTTP status, and network context.

The report is an evidence/configuration reference, not a claim that SlipNet can import the JSON directly. SlipNet's DNS scanner accepts the plain IP list. The JSON file preserves why each address qualified and which engine/profile was actually tested. Private keys and credentials are never exported.

The export query keeps the latest successful FULL attempt per resolver/profile. A later failed retry does not erase the last successful proof, but the report timestamp remains the time of that success. DNSTT and VayDNS remain separate records.
