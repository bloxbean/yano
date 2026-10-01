Reference `validate` request documents produced by the Rust reference encoder
(`amaru-validator-wasm/tests/amaru_scenarios.rs`, run with `AMARU_DUMP_REQUESTS_DIR`) for a few of
Amaru's scenarios at tag `v10.11.20260925`. `GoldenRequestEncodingTest` rebuilds each request from the
same scenario with the Java encoder and requires identical bytes.

The transactions and state inside them come from Amaru's scenario corpus (Apache-2.0; see
`amaru-validator-wasm/NOTICE` and `LICENSE-AMARU`).

Regenerate after an interface or Amaru bump:

    cd amaru-validator-wasm
    AMARU_FIXTURES_DIR=<amaru clone>/crates/amaru-ledger/tests/data/transaction \
      AMARU_DUMP_REQUESTS_DIR=target/scenario-requests cargo test --locked --test amaru_scenarios
    cp target/scenario-requests/<name>.full.req.cbor ../amaru-validator/src/test/resources/golden/
