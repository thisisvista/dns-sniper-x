# Iran prefix discovery

DNS SniperX can retrieve the official RIPE NCC delegated statistics file and retain
Iran (IR) IPv4 allocations/assignments. This is registry metadata, not a resolver list.

- Parse only IR IPv4 rows with allocated/assigned status.
- Convert arbitrary address ranges into an exact minimal CIDR cover.
- Sample 10 candidates per prefix by default (maximum 50).
- Resume deterministically by offset; batches are capped at 2,000.
- Exclude private, loopback, link-local, carrier-grade NAT, benchmarking,
  documentation and multicast/reserved address ranges.
- Persist samples as NOT_TESTED resolvers with CANDIDATE / REGISTRY_PREFIX evidence.
- Never promote a candidate to a working DNS resolver until DNS protocol tests pass.

Country allocation data does not identify Irancell, MCI, Rightel, Pars Online or another
retail ISP. Provider-specific discovery requires a current authoritative ASN/prefix
mapping reviewed for each provider. An ASN match is network attribution, not proof
that an IP runs public DNS. Provider ID remains null unless independent evidence exists.

Do not run full address-space scans by default. Keep discovery opt-in, scoped, rate-limited
and limited to modest batches. UDP/TCP DNS validation, response/hijack checks, EDNS,
DoH and tunnel compatibility are separate test stages.
