# Governed Join and Threshold Demo

Start with governed membership (the light YAML already does):

```bash
./showcase.sh up --nodes 3 --instance governance
```

Join the next node index:

```bash
./showcase.sh member join 3 --instance governance
```

For every configured chain, the launcher asks distinct ready current members
to submit the same signed `ADD` governance command. The existing threshold
finalizes it. The membership epoch activates later; only then is node 3
allowed to vote. Node 3 starts before that delayed height so it can catch up;
the command returns only after its tips and roots agree with an existing node.
Activate the scheduled epoch with explicit, normally sequenced showcase
traffic and finalize one proof block under the new profile. Because localhost
peer addresses are launcher configuration rather than consensus state, the
join command also refreshes existing processes one at a time with the expanded
peer set. Retained state and governed epochs are reused; no second membership
command is sent.

```bash
./showcase.sh governance activate --instance governance
```

Run this before scheduling another membership or threshold change. The
launcher rejects overlapping governed epochs because a later epoch could
otherwise replace a member change that has not activated yet.

Change 2-of-4 MPF finality to 3-of-4:

```bash
./showcase.sh threshold set 3 --instance governance
./showcase.sh governance activate --instance governance
./showcase.sh verify all --instance governance
```

Again, distinct current members approve under the old threshold. The new
threshold becomes active in a later membership epoch on every chain. Lower it
the same way:

```bash
./showcase.sh threshold set 2 --instance governance
```

This threshold controls app-chain block finality. It does not rewrite the
`required` voter count already stored inside an application approval proposal.
The business approval protocol and MPF consensus/finality protocol are
separate even when both happen to use the number two.

Nodes must join in index order: `3`, then `4`, and so on. `restart` restores
bootstrap members first and uses the retained-node `resume` path for recorded
joiners, so it never submits a second membership command. `config show`
reports active and scheduled members/thresholds plus the activation height;
the default governance lag is ten app-chain blocks. `governance activate`
submits a valid command for each configured state machine: an ordered-log
heartbeat, KV put, approval proposal, balance mint, document append, composite
KV put, role-policy probe, and signed virtual EUTxO self-payment. The role
probe is deliberately a well-formed business no-op. This is real MPF traffic,
not a database edit, so it advances height and produces finality certificates.
