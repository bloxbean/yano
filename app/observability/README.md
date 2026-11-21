# Yano observability bundle

Use `./yano.sh observability start` from an extracted distribution. The
launcher starts one pinned Prometheus container on loopback, scrapes
`http://127.0.0.1:7070` unless `--target` is given, and preserves its volume on
`stop`. Use `clean --yes` to remove the labeled volume and marked launcher
state. No Grafana or secret is bundled.
