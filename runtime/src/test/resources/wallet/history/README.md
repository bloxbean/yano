# Independent historical wallet comparison

After a `benchmarkWalletHistory` database with both indexes has completed:

```sh
./gradlew :runtime:verifyWalletHistory \
  -PwalletVerifyDatabase=/absolute/path/to/completed/both \
  -PwalletVerifyOutput=/absolute/path/to/fresh-verification-directory
python3 runtime/src/test/resources/wallet/history/verify.py /absolute/path/to/fresh-verification-directory
```

The Java exporter opens the completed RocksDB read-only, exports framed canonical
CBOR and query credentials, and runs the real filter scanner. It records matched
transaction/UTxO digests, candidate body reads and confirmed blocks. The Python
oracle requires `cbor2` and independently walks the raw CBOR, hashing original
transaction byte slices with Blake2b-256. It resolves selected wallet inputs from
prior effective outputs, includes supported event credentials, handles collateral
semantics, and compares final outpoints, lovelace, complete asset identities and
quantities. With both indexes present it also compares every Shelley-address
first-seen slot against effective raw outputs.

The oracle deliberately supports the retained preprod replay used here: empty
Shelley initial funds, contiguous numbered main blocks starting at one, and
Shelley payment/stake/DRep queries. Byron transactions have no such credentials;
Byron address first-seen is covered separately by the runtime tests. The exporter
rejects nonempty Shelley genesis instead of silently producing an incomplete
reference. These tools are verification utilities, not a node backfill feature.

The 1,000-block smoke comparison passes for its single available query: three
matching transactions, four final outputs and four exact first-seen addresses.
That smoke result is not the deferred million-block reference comparison.
