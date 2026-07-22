# `balances` State Machine

`balances` is Yano's stock non-negative account ledger. A configured member
may mint units to an application account, and each app-chain member may
transfer units only from the account named by that member's public-key hex.
Every current balance is threshold-finalized and individually provable against
the app-chain state root.

This is an application credit ledger, not Cardano ada or native assets. It does
not create L1 transactions, hold custody, calculate fees, or provide a token
policy.

## When to use it

Use `balances` for deliberately simple member-owned units:

- consortium netting or settlement inputs;
- loyalty, quota, or service credits;
- prepaid usage units;
- internal receipt balances; and
- demos that need deterministic mint/transfer/non-negative behavior.

Use a custom state machine when accounts belong to identities other than node
members, minting needs multiple roles or approvals, assets need multiple
denominations, transfers need holds or atomic swaps, or L1 settlement is part
of the state transition.

## Command and state model

The canonical CBOR commands are:

```text
[0, destinationAccount, positiveAmount]  MINT
[1, destinationAccount, positiveAmount]  TRANSFER
```

`MINT` credits the destination when the command sender is the configured
minter. `TRANSFER` debits the authenticated sender's account—its 32-byte member
public key as lowercase hex—and credits the destination. Insufficient funds,
unauthorized minting, and malformed commands are deterministic no-ops or are
rejected at admission; balances never become negative.

The authenticated state key is UTF-8 `b/<account>`. Its value is the positive
unsigned big-endian amount. A zero balance is represented by the absence of the
key.

## Configuration

```yaml
yano:
  app-chain:
    chain-id: credits-chain
    state-machine: balances
    machines:
      balances:
        minter: <64-hex-member-public-key>
```

An empty `minter` allows any member to mint and is suitable only when that is
the intended governance model. The generated `state:balances` capability can
be selected with `./yano.sh appchain init` or App-Chain Studio.

## Java client

Use `appchain-stdlib-contracts` when only canonical bytes and state decoders
are needed, or the typed facade in `appchain-client` for submission and local
proof verification:

```java
AppChainClient raw = AppChainClient.builder("http://localhost:7070/api/v1")
        .chainId("credits-chain")
        .apiKey(System.getenv("YANO_API_KEY"))
        .build();
StdlibAppChainClient balances = new StdlibAppChainClient(raw);

balances.mint("customer-42", BigInteger.valueOf(100));
balances.transfer("customer-42", BigInteger.TEN);

var verified = balances.balance("customer-42");
verified.ifPresent(value -> System.out.println(value.value()));
```

The transfer above spends the submitting member's public-key-hex account, not
`customer-42`. The returned message id proves acceptance, not that a transition
changed state; read the verified balance after finalization.

With the Spring starter, inject `StdlibAppChainTemplate` and call `mint`,
`transfer`, or `balance` with the same contract semantics.

## REST/curl

The generic REST endpoint accepts canonical command bytes. This example mints
10 units to `alice`; `830065616c6963650a` is CBOR `[0,"alice",10]`:

```bash
curl -sS -X POST \
  http://localhost:7070/api/v1/app-chain/chains/credits-chain/messages \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: $YANO_API_KEY" \
  -d '{"topic":"balances.command.v1","bodyHex":"830065616c6963650a"}'
```

Query the proof for state key `b/alice` (hex `622f616c696365`):

```bash
curl -sS \
  http://localhost:7070/api/v1/app-chain/chains/credits-chain/proof/622f616c696365 \
  -H "X-API-Key: $YANO_API_KEY"
```

Verify the MPF proof locally and, for audit-grade verification, compare its
root with an independently obtained anchor root.

## Customization boundary

Account naming, authorization, single-unit arithmetic, and deletion of zero
balances are consensus semantics. Configuration may select the minter but
cannot redefine those rules. Write a versioned custom state-machine plugin for
additional asset types, role-aware ownership, fees, locks, or settlement.
