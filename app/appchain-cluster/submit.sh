#!/usr/bin/env bash
# Convenience wrapper to submit an app-chain payload to a running cluster.
#
#   ./submit.sh <chain-id> <topic> <payload> [--node i] [--count n]
#
# Examples:
#   ./submit.sh orders-chain orders '{"id":1,"item":"widget"}'
#   ./submit.sh registry-chain kv "set:color=blue"       --node 1
#   ./submit.sh orders-chain load "burst" --count 50
#
# The payload is sent as UTF-8 bytes on the given topic to the chain-scoped
# endpoint POST /api/v1/app-chain/chains/<chain-id>/messages. It is gossiped to
# every member and finalized into a block on all nodes.
exec "$(cd "$(dirname "$0")" && pwd)/cluster.sh" submit "$@"
