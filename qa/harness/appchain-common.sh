#!/bin/bash
# App-chain two-node cluster helpers (isolated ports: A=7171/13441, B=7172/13442).
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
APIKEY=regression-admin-key

PUB_A=8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c
PUB_B=8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394
PUB_C=ca93ac1705187071d67b83c7ff0efe8108e8ec4530575d7726879333dbdabe7c
SEED_A=0101010101010101010101010101010101010101010101010101010101010101
SEED_B=0202020202020202020202020202020202020202020202020202020202020202
SEED_ANCHOR=0303030303030303030303030303030303030303030303030303030303030303
FPRINT=91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b
GENID=c2b9c92a865dfa7c218a1a6e49f1dd88163372e40466009876458c01609d0d70

A=http://localhost:$HTTP_A/api/v1
B=http://localhost:$HTTP_B/api/v1
FAILS=()
check() { # <label> <condition-exit-code>
  if [ "$2" = 0 ]; then echo "  PASS  $1"; else echo "  FAIL  $1"; FAILS+=("$1"); fi
}

# state triple for a chain prefix, e.g. state_triple yano.app-chain  or  yano.app-chain.chains[0]
state_triple() {
  echo "-D$1.state.commitment-profile=mpf-blake2b256-v1 -D$1.state.format-fingerprint=$FPRINT -D$1.state.genesis-id=$GENID"
}

# start_node_a <run> <extra args...>   (devnet BP, genesis epochLength 500)
start_node_a() {
  local run=$1; shift
  jq '.epochLength = 500' "$REPO/app/config/network/devnet/shelley-genesis.json" > "$run/genesis-a.json"
  start_yano jvm "$run/a" $HTTP_A $N2N_A "$run/node-a.log" -Dquarkus.profile=devnet \
    -Dyano.genesis.shelley-genesis-file=$run/genesis-a.json \
    -Dyano.app-chain.storage.path=$run/a/appchain -Dyano.app-chain.api.keys=$APIKEY "$@"
  A_PID=$YANO_PID
}

# start_node_b <run> <extra args...>   (L1 follower of A; copies A's rewritten genesis)
start_node_b() {
  local run=$1; shift
  cp "$run/genesis-a.json" "$run/genesis-b.json"
  start_yano jvm "$run/b" $HTTP_B $N2N_B "$run/node-b.log" -Dquarkus.profile=devnet \
    -Dyano.genesis.shelley-genesis-file=$run/genesis-b.json \
    -Dyano.app-chain.storage.path=$run/b/appchain -Dyano.app-chain.api.keys=$APIKEY \
    -Dyano.block-producer.enabled=false -Dyano.dev-mode=false -Dyano.client.enabled=true \
    -Dyano.remote.host=localhost -Dyano.remote.port=$N2N_A "$@"
  B_PID=$YANO_PID
}

wait_l1_sync_b() { # wait until B's L1 tip is within 20 slots of A
  for i in $(seq 1 60); do
    local sa sb; sa=$(yano_tip $HTTP_A | jq -r '.slot // 0'); sb=$(yano_tip $HTTP_B | jq -r '.slot // 0')
    [ "$sb" -gt 0 ] && [ $(( sa - sb )) -le 20 ] && { log "B L1 synced (A=$sa B=$sb)"; return 0; }
    sleep 2
  done
  log "B L1 NOT synced (A=$sa B=$sb)"; return 1
}

APIKEY=regression-admin-key
post() { curl -s -X POST "$1" -H 'Content-Type: application/json' -H "X-API-Key: $APIKEY" -d "$2"; }

l1_lockstep() {
  local a1 b1 a2 b2
  a1=$(yano_tip $HTTP_A | jq -r .blockNumber); b1=$(yano_tip $HTTP_B | jq -r .blockNumber); sleep 5
  a2=$(yano_tip $HTTP_A | jq -r .blockNumber); b2=$(yano_tip $HTTP_B | jq -r .blockNumber)
  echo "  L1 A:$a1->$a2 B:$b1->$b2"
  [ $(( a2 - b2 )) -le 2 ] && [ $(( b2 - a2 )) -le 2 ] && [ "$b2" -gt "$b1" ]
}

error_scan() { # <run>
  for n in a b; do
    local f="$1/node-$n.log"
    echo "  node-$n ERROR lines: $(grep -c ' ERROR ' "$f")  (app-chain related: $(grep ' ERROR ' "$f" | grep -ciE 'app.?chain|appchain|anchor|sequenc|consensus|observ'))"
    grep ' ERROR ' "$f" | cut -c1-230 | sed -E 's/^[0-9-]+ [0-9:,]+ //' | sort | uniq -c | sort -rn | head -5
  done
}

# JSON-building helpers: avoid escaped quotes inside "$(...)" (word-splitting bug).
fund() { post "$1/devnet/fund" "$(jq -nc --arg a "$2" --argjson n "$3" '{address:$a, ada:$n}')"; }
add_member() { post "$1" "$(jq -nc --arg k "$2" '{publicKey:$k}')"; }
submit() { post "$1" "$(jq -nc --arg t "$2" --arg b "$3" '{topic:$t, body:$b}')"; }
