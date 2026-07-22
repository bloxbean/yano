# App-chain standard-library contracts

This no-SPI artifact contains the bounded, versioned command, state-key, and
state-value codecs for Yano's stock `kv-registry`, `approvals`, `balances`, and
`doc-trail` state machines. Applications may depend on it without pulling in a
node runtime or state-machine implementation.

The contracts accept only canonical definite-length CBOR and enforce a 1 MiB
wire bound. The artifact contains no plugin manifest, `ServiceLoader` entry,
network client, storage code, or runtime SPI implementation.
