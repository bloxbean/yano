# Yano App-Chain Standard Library

Ready-to-use `AppStateMachine` implementations for Yano app chains.

This module is packaged into the default distribution and discovered through the
`AppStateMachineProvider` ServiceLoader. Select a machine with:

```properties
yano.app-chain.state-machine=kv-registry
```

See also:

- [App-chain user guide](../../docs/APP_CHAIN_USER_GUIDE.md)
- [ADR-006 enterprise extensions](../../adr/app-layer/006-appchain-enterprise-extensions-and-zk.md)

## State Machines

| ID | Purpose |
|---|---|
| `kv-registry` | Key/value registry with first-writer ownership. Only the owner can update or delete a key. |
| `approvals` | Deterministic k-of-n approval workflows with terminal approved/rejected/expired states. |
| `balances` | Simple member-authorized account balances with mint/transfer operations and non-negative balances. |
| `doc-trail` | Append-only per-entity document/event trail with chained heads. |

## `kv-registry`

Command body format:

```text
[op(uint), key(bstr), value(bstr)]
```

Operations:

- `0`: put
- `1`: delete

Helper methods:

```java
byte[] body = KvRegistryStateMachine.put(keyBytes, valueBytes);
byte[] delete = KvRegistryStateMachine.delete(keyBytes);
```

State entry:

```text
key -> cbor([owner(bstr .size 32), value(bstr)])
```

## `approvals`

Command body formats:

```text
[0, itemId(tstr), payload(bstr), required(uint), deadlineMillis(uint)]
[1, itemId(tstr)]
[2, itemId(tstr)]
```

Operations:

- `0`: propose
- `1`: approve
- `2`: reject

Deadlines use the app block timestamp, so all members make the same decision
when replaying the block.

## `balances`

Command body formats:

```text
[0, to(tstr), amount(uint)]
[1, to(tstr), amount(uint)]
```

Operations:

- `0`: mint
- `1`: transfer

Transfers debit the sender's account, where the sender account is the member
public key hex. A transfer with insufficient funds is a deterministic no-op.

## `doc-trail`

`doc-trail` stores an append-only trail per entity. Each entry advances a chained
head so consumers can prove the latest head against the app-chain MPF state root
and verify the ordered trail off-chain.

## Test

```bash
./gradlew :appchain-stdlib:test
```

## Determinism

These machines are written to be deterministic under block replay:

- no wall-clock reads in `apply`
- no randomness
- no external services
- no local file reads
- all rejected application commands become deterministic no-ops or admission
  rejections

Custom state-machine plugins should follow the same rules.
