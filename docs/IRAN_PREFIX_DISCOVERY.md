# Iran and provider prefix discovery

DNS SniperX retrieves RIPE NCC delegated statistics and RIPEstat announced-prefix data.
It does not treat a country allocation as a DNS server list.

## Country-wide candidate discovery

- RIPE delegated rows are filtered to IR IPv4 allocations/assignments.
- Address ranges are converted to an exact minimal CIDR cover.
- Default sample size is 10 addresses per prefix (maximum 50).
- Deterministic offset and batch size make sampling resumable.
- Each batch is capped at 2,000 candidates.
- Private, loopback, link-local, carrier-grade NAT, benchmarking, documentation,
  multicast and reserved ranges are excluded.
- Persisted candidates remain NOT_TESTED with CANDIDATE / REGISTRY_PREFIX evidence.

## Provider-specific discovery

The app has a narrow seed mapping for primary ASNs: MCI AS197207, Irancell AS44244,
Rightel AS57218, TCI AS58224, Shatel AS31549, Asiatech AS43754, Pars Online AS16322,
Mobinnet AS50810, HiWeb AS56402, Pishgaman AS49100, Respina AS42337, Afranet AS25184 and Fanap Telecom AS24631. Shatel Mobile has no
separate seed because it operates as an MVNO and may use its host network; Sabanet is
left unmapped until a reliable current ASN-holder mapping is confirmed.

For each selected seed, the app requests current announced IPv4 prefixes from the RIPEstat
Announced Prefixes API (minimum 10 RIS peers), then intersects those prefixes with current
IR delegated allocations. Only the intersections are sampled and tagged with provider ID
and ASN evidence. The ASN seed list is a maintained starting catalog, not a claim that
these are every ASN used by each retail provider. Review/update it against current holder
records before expanding it. The source project for the seed list is
https://github.com/Kiya6955/IR-ISP-Blocker ; RIPEstat API docs:
https://stat.ripe.net/docs/data-api/api-endpoints/announced-prefixes

ASN routing evidence indicates which network announces a prefix. It still does not prove
that the address runs public DNS or that the entire prefix is dedicated to that ISP's
retail customers. Provider assignment remains CANDIDATE until stronger evidence exists.
A candidate becomes a working DNS only after protocol validation (UDP/TCP, response
integrity and hijack checks); EDNS, DoH and DNSTT/Slipstream compatibility are separate.

Do not run full address-space scans by default. Keep discovery opt-in, scoped, rate-limited,
and limited to modest batches. The registry/API fetches are sequential and bounded.
