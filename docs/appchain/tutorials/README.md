# App-Chain Tutorials

These tutorials are progressive but independently usable. Each one has a
beginner path and a **Go deeper** section for readers who want the trust,
consensus, proof, or operational details.

| Tutorial | Typical time | Primary outcome |
|---|---:|---|
| [1. Your first app chain](01-first-app-chain.md) | 15 min | Three members finalize the same event history |
| [2. Registry and proofs](02-registry-and-proofs.md) | 15 min | Owner-controlled data with an MPF proof |
| [3. Stock state machines](03-stock-state-machines.md) | 20 min | Choose the smallest built-in application model |
| [4. Evidence publication](04-evidence-publication.md) | 30 min | S3/IPFS/Kafka effects plus proofs and an anchor |
| [5. Domain-role approvals](05-domain-role-approvals.md) | 30 min | Business actors and organization-distinct authorization |
| [6. Webhook effects](06-webhook-effects.md) | 20 min | Finalized decision invokes an external HTTP endpoint |
| [7. Anchors and verification](07-anchors-and-verification.md) | 20 min | Connect an application proof to Cardano settlement |
| [8. Plugins and composites](08-plugins-and-composites.md) | 30–60 min | Extend Yano without rebuilding or forking core |
| [9. From demo to pilot](09-from-demo-to-pilot.md) | planning | Convert local assumptions into an operable deployment |

## Tutorial conventions

- Commands are shown from the repository root unless a preceding `cd` changes
  directory.
- Local devnet data is disposable. `stop` preserves it; `clean` deletes it.
- Ports `7070`–`7072` are the expected member HTTP ports and `7080` is the
  Evidence Explorer. Launchers report a different range if defaults are busy.
- Demo credentials are intentionally known or generated locally. Never reuse
  them outside an isolated development environment.
- A successful HTTP submission means “accepted for sequencing,” not “already
  finalized.” Wait for the block/tip or use the scenario verifier.
- An invalid but finalized command can be a deterministic no-op. Always verify
  state, not merely the HTTP response or block height.

## Confidence levels used here

- **Shipped:** present in the current branch and covered by module tests.
- **Demo-proven:** exercised by the packaged multi-member demo and its
  connector/proof verification.
- **Preview:** useful for devnet/testnet or a tightly controlled pilot, with a
  named production-hardening boundary.
- **Experimental:** not a production claim; follow its dedicated guide.

Return to the [start-here hub](../README.md).
