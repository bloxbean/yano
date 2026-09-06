# Certified observation threat model (preview)

The safety claim is about deterministic certification under pinned source,
reporter, evidence and consensus assumptions. It is not a claim that arbitrary
external data is true. ADR-037's full encoding, quorum and scheduling rules
remain normative. The feature stays disabled by default pending qualification.

| Threat or boundary | Control and residual risk |
| --- | --- |
| Source lies or equivocates | Pinned authorized evidence and reconciliation policy; certificates can authenticate a lie if the trusted source lies. Signed Merkle inclusion is not physical delivery or completeness. |
| Correlated reporters/sources | Separate reporter and source quorums, pinned alias groups and complete-source coverage; multiple validators querying one source still constitute one source. |
| Byzantine reporters | Membership/external-set pinning, quorum intersection, evidence verification and per-round signing journal. Compromised keys beyond the assumed fault bound invalidate the safety assumption. |
| Wrong chain/profile/round replay | Domain-separated identities bind generation, definition, parameters, source and round. Membership is pinned when a round opens; later membership does not retroactively replace it. |
| Divergent sufficient report subsets | Deterministic exact/complete-source policies must yield identical result bytes/IDs; certificate audit digests may differ. Permutation/subset tests are mandatory. |
| Missing or selectively withheld reports | No fabricated value; bounded collection/inclusion windows and expiry. A faulty proposer can delay inclusion; live qualification must demonstrate honest recovery within the protocol assumptions. |
| Wake abuse | Raw subscription-ID hints only, SUBMIT access, bounded/coalesced local scheduling and shared acquisition limits. Acceptance is not proof of work, freshness or result. |
| SSRF/resource exhaustion | Restricted HTTPS destination/TLS/redirect/framing limits, bounded proof parsers, workers, journal capacity and indexed scheduling. Operator-installed custom plugins remain trusted code. |
| Crash or cloned signing identity | Claim persisted before signing, journal ownership and fail-closed recovery. Never run copied journals/signers concurrently or discard an inconvenient signing history. |
| Serving-node proof forgery | Native proof plus complete certified header, COMMIT signatures, independently pinned genesis/profile/members/quorum and height-specific consensus context. Copying a trust pin from the response defeats this boundary. |
| Effect receipt confused with settlement | The shipment example requires a separate stable host-validated L1 fact matching the release transaction, address and amount. It is not a production escrow/custody contract. |
| Local repair erases evidence | Recognized legacy-cursor repair requires exact committed authority and preserves quarantine/failure barriers. Unknown corruption or a deep finalized rollback remains a hard stop. |

Private reporter/validator keys and source credentials must not enter public
claims, logs or evidence. Credential compromise requires the applicable
governance/rotation procedure, not silently editing a retained generation.

APP_HEIGHT is logical chain progress. Without verified L1-slot scheduling or
a separately reviewed consensus-time design, an interval is not a duration.
Idle chains, partitions and restarts must not acquire invented wall-clock
authority. No mutable shared-feed or large ZK/TEE verifier is implicitly
approved by this implementation.

Independent review must challenge the above assumptions, especially quorum
intersection, terminal/stale-result precedence, atomic commit/recovery,
generation fencing, transport bounds and proof trust pinning. Unit tests,
self-review and local three/five-node tests do not replace that review or
the actual five-node Preprod qualification gate.
