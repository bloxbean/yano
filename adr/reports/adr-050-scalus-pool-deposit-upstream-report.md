# Scalus upstream report draft: pool updates are charged a new pool deposit

## Affected version

`org.scalus:scalus-cardano-ledger_3:0.18.2`. The same implementation is present
on Scalus `master` as checked on 2026-08-31. Bytecode inspection of the latest
Maven Central release, `1.1.1`, also confirms that `produced` still has no
`CertState` parameter.

## Problem

`TxBalance.consumed` accepts `CertState`, but `TxBalance.produced` accepts only
the transaction and protocol parameters. Its `conwayTotalDepositsTxCerts` calls
`Certificate.shelleyTotalDeposits`, which adds `stakePoolDeposit` for every
`PoolRegistration` certificate.

Scalus `1.1.1` bytecode narrows the missing state to one private function.
Consumed-side staking and DRep refunds use
`lookupStakingDeposit$1(CertState, Credential)` and
`lookupDRepDeposit$1(CertState, Credential)`, while produced-side certificate
deposits use `conwayTotalDepositsTxCerts(Transaction, ProtocolParams)` with no
state parameter. Threading the transaction-start pool set through that method
and `produced` would make pool deposits state-aware in the same way refunds
already are.

The Cardano ledger charges that deposit only for a pool ID absent from the
transaction-start `psStakePools` map. Re-registering an active pool is an update
and must not pay another deposit. Duplicate registrations for one new pool in a
single transaction are also charged only once.

## Minimal reproduction

1. Put pool `P` in `State.certState.pstate.stakePools` with its existing deposit.
2. Build a balanced transaction containing a `PoolRegistration` update for `P`.
3. Run `CardanoMutator.transit`.
4. `ValueNotConservedUTxOValidator` reports produced value larger than consumed
   value by exactly `protocolParams.stakePoolDeposit`.

The same failure occurs for both `PoolRetirement(P) -> PoolRegistration(P)` and
the reverse certificate order because retirement schedules future removal; it
does not remove `P` from the transaction-start active pool map.

## Suggested fix

Pass `CertState` (or an `isPoolRegistered` predicate derived from its
transaction-start pool map) into the produced/deposit calculation. Compute pool
deposits from the distinct pool IDs registered by the transaction that are not
already present in `pstate.stakePools`. Add tests for active update, new pool,
duplicate new registration, mixed active/new registrations and both retirement
orders.
