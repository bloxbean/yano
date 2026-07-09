# Yano App-Chain ZK Extension

Experimental ZeroJ-based state-machine plugin for proof-verified app-chain
messages.

This module is optional and not on the default app-chain execution path. It is
intended for experiments and controlled pilots where all members understand the
ZeroJ maturity and circuit-governance assumptions.

See also:

- [ADR-006 ZK section](../../../adr/app-layer/006-appchain-enterprise-extensions-and-zk.md)
- [ZeroJ project notes in ADR-006](../../../adr/app-layer/006-appchain-enterprise-extensions-and-zk.md#related)

## State Machines

| ID | Purpose |
|---|---|
| `zk-gate` | Verifies an in-body ZK proof before recording the message. |
| `zk-membership` | Verifies anonymous membership proofs and deduplicates actions by nullifier. |
| `credential-registry` | Records BBS-signed credentials and supports selective disclosure verification helpers. |

Proof checks are enforced in `apply()`, so every member re-verifies as part of
consensus replay. Admission-time verification is only a fast-fail path.

## Build

```bash
./gradlew :appchain-zk:jar
```

Place the plugin jar and required ZeroJ verifier backend jars in the node plugin
directory configured by `yaci.plugins.directory`.

## `zk-gate` Configuration

```properties
yano.app-chain.state-machine=zk-gate

yano.app-chain.zk.circuits[0].id=credit-limit
yano.app-chain.zk.circuits[0].vk-file=/etc/yano/credit-limit.vk
yano.app-chain.zk.circuits[0].vk-hash=<blake2b-256-hex-of-vk-file>
yano.app-chain.zk.circuits[0].proof-system=groth16
yano.app-chain.zk.circuits[0].curve=bls12381

# optional
yano.app-chain.zk.max-proofs-per-block=200
```

The verifier key file is hashed at startup and compared with the pinned hash.
A mismatch fails startup.

## `zk-membership`

```properties
yano.app-chain.state-machine=zk-membership

yano.app-chain.zk.circuits[0].id=member-vote
yano.app-chain.zk.circuits[0].vk-file=/etc/yano/member-vote.vk
yano.app-chain.zk.circuits[0].vk-hash=<blake2b-256-hex-of-vk-file>
yano.app-chain.zk.circuits[0].proof-system=groth16
yano.app-chain.zk.circuits[0].curve=bls12381
```

The nullifier must be bound to the proof as a public input. Repeated nullifiers
are deterministic no-ops in committed state.

## `credential-registry`

```properties
yano.app-chain.state-machine=credential-registry

yano.app-chain.zk.bbs.issuers[0].id=issuer-a
yano.app-chain.zk.bbs.issuers[0].public-key=<bbs-public-key-hex>
```

Issuers are application-level credential issuers and are separate from app-chain
member keys.

## Test

```bash
./gradlew :appchain-zk:test
```

## Important Limitations

This module is not a zk-rollup. It does not provide:

- full state-transition validity proofs
- private balances
- on-chain verifier anchoring
- anonymous transport
- production-audited circuits

Treat it as experimental ZK verification inside a permissioned app chain.
