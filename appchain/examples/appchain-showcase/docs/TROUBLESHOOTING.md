# Troubleshooting

## Retained L1 state has no identity marker

The launcher intentionally refuses to invent identity metadata around retained
state. Restore the original marker/profile, explicitly migrate the state, use
a new instance, or reset only the deliberately disposable instance. Do not
delete the check while keeping the data.

## Script-anchor co-sign round failed

Check node 0’s log. Typical causes are an unsynchronized L1 view, funding that
is not visible/stable yet, a bootstrap output that members cannot verify, an
insufficient/fragmented anchor wallet, or fewer ready current members than the
app-chain threshold. Creating a fresh wallet does not fix member/L1 visibility.
After a governed join, current showcase releases automatically refresh every
existing process's peer topology. If an older deployment repeatedly collects
one fewer witness than the member count, restart it with the current showcase
launcher before changing membership or funding.

## Effect Executors is empty

This is correct on chains that emit no effects and on follower nodes. In the
light profile, node 0 owns `showcase-outbox` for `workflow-chain`; every member
still validates/finalizes the intent and its result. Use node 0’s UI and run a
composite release first.

## Proposal is approved but no effect appears

Stock approval status does not automatically trigger the showcase composite
workflow. The application sends a separate release command after approval.
That command checks the exact order/payload hash, claims the release ID,
updates audit/release state, and emits the effect.

## Ports are occupied

Choose explicit non-overlapping bases:

```bash
./showcase.sh up --instance alternate --http-base 7170 --server-base 14337
```

## A prepared evidence devnet fails before its first block

The evidence devnet deliberately uses short generated epochs. `prepare`
creates its immutable devnet identity and `systemStart`; normally use
`quickstart` directly or follow `prepare` with `up` promptly. If a disposable
instance was prepared and then left idle for multiple epochs before it ever
started, use a fresh `--instance` name. Do not edit the retained genesis or
identity marker in place. Once an instance has history, normal stop/start
reuses that exact identity.

## A joined node will not start after restart

Use the showcase `restart`, not a direct `cluster.sh start`, because the facade
restores bootstrap nodes and then reattaches its recorded governed joiners.
Inspect `config paths` and node logs before changing retained files.
