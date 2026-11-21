# Provider ASN-prefix discovery

Provider discovery is a separate, bounded candidate-generation stage. It does not infer that a sampled IP is a DNS resolver and does not claim ISP ownership is verified.

## Runtime contract

- Call `ProviderDiscoveryCoordinator.discoverNextBatch(providerIds)` with one or more reviewed provider IDs.
- The selected provider IDs are independent of the phone's active network or SIM operator. For example, a device online through Irancell can discover TCI candidates.
- A stable selection key stores the cursor in Room. Candidate rows, RIPEstat provenance, evidence, and the next cursor are committed in one transaction.
- Re-running after interruption resumes at the saved offset. A completed selection is not downloaded again unless its state is reset.
- Candidates use source `provider-asn-prefix-sample` and remain `NOT_TESTED` with ownership status `CANDIDATE`.
- The normal PUBLIC scan filter includes provider-prefix candidates, including endpoints previously merged into another resolver source.
- DNS health and provider ownership require separate protocol and ownership evidence.

The coordinator processes one bounded page at a time. The caller should invoke it from an explicit user action and report page progress; it must not be run as an always-on background task.
