# Presenter Cheat Sheet

```bash
./showcase.sh doctor
./showcase.sh quickstart --instance demo --nodes 3
./showcase.sh ui --instance demo
./showcase.sh config show --instance demo
```

The core story is:

1. three members sequence and threshold-finalize independent app chains;
2. all nodes derive the same state root and MPF-provable state;
3. every application declares its components and cross-cutting capabilities;
4. business approval messages are separate from MPF finality votes;
5. the composite release emits an immutable effect intent;
6. node 0 writes one idempotent local receipt after app finality;
7. the result re-enters the chain and changes root-attested release state;
8. a Cardano anchor commits the finalized L2 root; and
9. governed members can add a node and change threshold without editing live
   membership state by hand.

For the governed portion, always activate one scheduled epoch before creating
the next one:

```bash
./showcase.sh member join 3 --instance demo
./showcase.sh governance activate --instance demo
./showcase.sh threshold set 3 --instance demo
./showcase.sh governance activate --instance demo
./showcase.sh verify all --instance demo
```

If time is short, run only `quickstart`, open the UI, then show:

```bash
./showcase.sh run composite --instance demo
./showcase.sh run document-review --instance demo
./showcase.sh config show --instance demo
./showcase.sh status --instance demo
```

Use [MASTER_DEMO.md](MASTER_DEMO.md) for the full narration and expected
checkpoints. `./demos/master-demo.sh` drives the same flow with pauses.
