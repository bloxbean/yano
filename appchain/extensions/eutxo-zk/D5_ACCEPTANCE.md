# D5 network acceptance

<a id="network-evidence"></a>

## Network evidence

D5 provides an executable packaged-lifecycle gate and a versioned evidence
format. It does not convert missing public-network work into a passing result.

The canonical evidence is
[`acceptance/network-acceptance-v1.json`](acceptance/network-acceptance-v1.json),
validated by
[`acceptance/eutxo-zk-network-acceptance.schema.json`](acceptance/eutxo-zk-network-acceptance.schema.json).
Both files are copied into the JVM distribution.

Current outcome:

| Network | Packaged policy/lifecycle | Live L1 deposit → L2 → proof → L1 withdrawal |
|---|---|---|
| Yano devnet | `PASSED` | `NOT_EXERCISED` |
| Cardano Preview | `PASSED` with durable acknowledgement | `NOT_EXERCISED` |
| Cardano Preprod | `PASSED` with durable acknowledgement | `NOT_EXERCISED` |
| Cardano mainnet | `REJECTED` | `REJECTED` |

“Packaged lifecycle” means the final release ZIP can generate the exact
recipe, validate it, bootstrap the deterministic contract plan, report
status, enforce public-testnet acknowledgement, and reject mainnet using only
files shipped in the archive. It does not mean the planned validators were
deployed or any test ADA moved.

The live rows stay `NOT_EXERCISED` until retained evidence demonstrates:

1. refundable test-ADA deposit staging and stable mirrored credit;
2. at least one maximum-profile b16 finalized L2 batch;
3. proof generation, independent verification, root/batch-data publication,
   and stable root observation;
4. proof-gated withdrawal to an ordinary Cardano address;
5. restart, duplicate submission, process crash, rollback, unavailable
   prover, unavailable data, and recovery scenarios;
6. cross-node root agreement and independent reconstruction; and
7. exact script, protocol-parameter, ceremony, key, profile, transaction, and
   block identities in the evidence bundle.

Preview and Preprod additionally require funded testnet operator credentials
and network access. Those resources are external to the repository and their
absence is represented as `NOT_EXERCISED`, not as a build failure or a pass.

## Packaged acceptance command

```bash
./gradlew :appchain-devtools:test \
  --tests \
  'com.bloxbean.cardano.yano.appchain.devtools.AppChainFinalDistributionAcceptanceTest.finalDistributionRunsEutxoValidityLifecyclePolicy'
```

The test extracts the final `yano-{version}.zip`, then verifies:

- the evidence and schema are present;
- devnet project generation, validation, plan bootstrap, and status work;
- Preview and Preprod reject missing acknowledgements and accept a retained
  acknowledgement;
- mainnet initialization is rejected; and
- all commands execute through the packaged `./yano.sh`, not a source-tree
  classpath.

The broader D5 exit criteria in ADR-UTXO-001 remain open until the live rows
change with reviewable evidence.
