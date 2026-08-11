# App-chain Proof Lab

The generic console exposes **Operations**, **Capabilities**, and **Proofs**. Proofs has four
workflows: **Message**, **State**, **Import and verify**, and **Advanced**. The first two describe a
fact in application language; Advanced retains the physical-key diagnostic endpoint.

## What each result means

Keep these statements separate:

| Result | Meaning |
|---|---|
| Message inclusion | The message ID is a leaf under one app block's `messagesRoot`. |
| Finality certificate | A caller-pinned membership threshold signed the block. |
| Authenticated block record | Finalized state contains `[height, messagesRoot, messageCount]`. |
| State recording | The application wrote a typed fact under its canonical state key. |
| Anchor binding | A trusted Cardano output commits the selected application identity/root. |
| Claim satisfied | The proof-carried canonical value satisfies the selected bounded predicate. |
| Locally retained | This node has the bytes now; durable availability remains `NOT_PROVEN`. |

A proof that reconstructs the root is only `INTERNAL_CONSISTENCY_ONLY` until the root is pinned by
the caller, a pinned finality policy, or an independently checked Cardano script output. A
node-reported anchor is labelled `NODE_CONFIRMED_L1_REFERENCE`, not independently trusted L1 truth.

## Operator guide

New chains enable `state-index:finalized-block-messages-v1` by default. Its immutable settings are:

```properties
machines.finalized-block-message-root.enabled=true
machines.finalized-block-message-root.max-messages-per-block=<consensus block limit>
machines.finalized-block-message-root.retention-profile=primary
```

Disable it only in a new genesis profile. The enabled flag and configuration digest change the
application identity. An existing database with a different configuration fails startup instead of
silently drifting. The initial release retains block records in primary authenticated state; use an
explicitly disabled profile for very high-rate indefinite chains until segmented snapshot retention
is qualified.

The raw payload is one 32-byte key plus a canonical CBOR value of approximately 38–47 bytes,
depending on height and count integer widths. That is 70–79 logical bytes per block before MPF/JMT
nodes and RocksDB amplification. The raw-key/value projection is approximately:

| Block interval | Records/day | Raw logical growth/day |
|---|---:|---:|
| 1 second | 86,400 | 5.8–6.5 MiB |
| 5 seconds | 17,280 | 1.15–1.30 MiB |
| 20 seconds | 4,320 | 0.29–0.33 MiB |

Capacity planning must measure backend node amplification, compaction, snapshots, and retained proof
history on the intended workload. Monitor `oldestProvableHeight`; a pruned proof is unavailable, not
evidence that the fact was absent.

## Application-author guide

Implement `ProofSubjectProvider` next to the module that owns the canonical key and value codec.
Each descriptor is closed data: coordinates, fact fields, claims, completeness, verification targets,
retention hints, and fixed bounds. Put its `descriptorDigest` in the capability manifest. The runtime
activates the provider only when subject ID, version, component ID, and digest all match.

Resolution must be deterministic and side-effect free. Derive the canonical key from normalized
coordinates, decode only the value carried by the verified proof, and evaluate only declared claims.
Do not expose a business-level absence predicate unless a marker, paired subject, or authenticated
snapshot descriptor proves dataset completeness.

Stock v1 subjects cover finalized block messages, finalized message records, balances, registry
entries, document heads, basic approval outcomes, authenticated-map entries, actor roles, role
approval outcomes, and composite profile markers. Composite subjects are automatically rebound to
their component namespace and get component-qualified IDs.

## Plugin-author guide

A plugin may return data-only providers from `AppStateMachine.proofSubjectProviders()`. It cannot
inject JavaScript, HTML, styles, URLs, expressions, or code locations into the console. Startup
rejects undeclared subjects, descriptor digest mismatch, collisions, excessive descriptor counts,
duplicate coordinates/claims, oversized fields, and hostile presentation text. Plugin lifecycle
callbacks remain isolated by the existing plugin facade.

## Independent-verifier guide

For `appchain-message-proof-v1`, discard `verification`, recompute the binary message path, bind the
message ID/content, verify the authenticated block record or signed block segment, pin membership and
commitment identity, then independently resolve the Cardano thread-token output when L1 authenticity
is required. Availability is always a separate observation.

For `appchain-state-claim-proof-v1`, discard `verification` and presentation fields. Match the
descriptor digest to release-matched code, rederive the logical and physical key from coordinates,
verify MPF/JMT against the caller's exact identity/root/height, decode the proof-carried bytes, verify
completeness when required, and evaluate the claim. A false claim over an authentic proof is rejected.

The server endpoints are:

```text
GET  /api/v1/app-chain/chains/{chain}/proof-subjects
POST /api/v1/app-chain/chains/{chain}/proof-subjects/{subject}/proof
POST /api/v1/app-chain/chains/{chain}/proof-subjects/{subject}/package
POST /api/v1/app-chain/chains/{chain}/proof-subjects/{subject}/onchain-export
GET  /api/v1/app-chain/chains/{chain}/messages/{messageId}/proof-package
```

The browser recomputes compact message paths and normalized MPF exports. Java clients use
`MessageProofVerifier` and `StateClaimProofVerifier`. JMT subjects are explicitly off-chain only.

## Cardano-validator guide

`BlockMessageRootAnchorValidator` is the reference nested MPF validator. It selects the unique
expected anchor thread-token output, checks script/application/genesis/profile/fingerprint binding,
verifies the MPF inclusion of the exact block-record key/value, strictly decodes canonical
`[1,height,messagesRoot,messageCount]`, then verifies the bounded Blake2b-256 binary message path.

Released bounds are 256 key bytes, 8 KiB value bytes, 32 MPF folds, 10,000 messages, and 14 binary
path levels. Odd message levels duplicate the final leaf and the sibling must equal the current node.
The export endpoint verifies native MPF before normalization and labels output
`NOT_YET_EXECUTED_ON_CHAIN`; transaction construction and evaluation must still enforce the target
network's current size/CPU/memory limits.

## Presenter scenarios

1. Prove a finalized message and point out inclusion, signed finality, state-root binding, anchor
   provenance, content binding, and `NOT_PROVEN` availability separately.
2. Prove a document head digest and revision; then change only the expected digest to demonstrate an
   authentic proof with a false semantic claim.
3. Prove balance minimum/maximum and an approval status or quorum result.
4. Prove an authenticated-map entry's status/revision/value digest.
5. Compare the default one-record-per-block profile, an explicitly disabled zero-write profile, and
   the optional per-message index. Explain that MPF supports qualified on-chain export while JMT is
   off-chain only.
