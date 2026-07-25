# Yano observability bundle

Use `./yano.sh observability start` from an extracted distribution. The
launcher starts one pinned Prometheus container on loopback, discovers a
running local app-chain cluster when possible, and preserves its volume on
`stop`. Use `clean --yes` to remove the labeled volume and marked launcher
state. No Grafana or secret is bundled.
