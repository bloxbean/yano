#!/usr/bin/env bash
# ADR-060: scores Yano's node-diversity reports with the site's own builder, as the site will score them.
#
#   score-reports.sh <reports-dir> <node-diversity-conformance checkout>
#
# <reports-dir> holds yano-<version>-<yyyymmdd>-<scenario>.json (build/node-diversity). The builder runs in preview
# mode on a temporary copy of the checkout, beside the nodes whose reports the checkout carries under
# scenarios/*/preview (cardano-node and Amaru): uplc-turbo checks each budget against the majority of nodes. Nodes
# without local reports are dropped, so nothing is downloaded. Prints one line per scenario, then the overall score.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <reports-dir> <node-diversity-conformance checkout>" >&2
    exit 2
fi
REPORTS=$(cd "$1" && pwd)
SITE=$(mktemp -d)
trap 'rm -rf "$SITE"' EXIT
cp -R "$2/." "$SITE"
for node_dir in "$SITE"/nodes/*/; do
    node=$(basename "$node_dir")
    if compgen -G "$SITE/scenarios/*/preview/$node.json*" >/dev/null; then
        python3 - "$node_dir/metadata.json" <<'PY'
import json
import sys

metadata = json.load(open(sys.argv[1]))
metadata["reports"] = {}
json.dump(metadata, open(sys.argv[1], "w"))
PY
    else
        rm -rf "$node_dir"
    fi
done
rm -f "$SITE"/scenarios/*/preview/yano.json*
mkdir -p "$SITE/nodes/yano"
printf '{"id": "yano", "name": "Yano", "language": "Java", "reports": {}}\n' > "$SITE/nodes/yano/metadata.json"
printf 'Yano\n' > "$SITE/nodes/yano/README.md"
printf '<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"/>\n' > "$SITE/nodes/yano/logo.svg"

for scenario_dir in "$SITE"/scenarios/*/; do
    scenario=$(basename "$scenario_dir")
    report=$(find "$REPORTS" -maxdepth 1 -name "yano-*-$scenario.json" | sort | tail -1)
    if [[ -n "$report" ]]; then
        mkdir -p "$scenario_dir/preview"
        cp "$report" "$scenario_dir/preview/yano.json"
    fi
done

(cd "$SITE" && python3 scripts/build.py --preview >/dev/null)
python3 - "$SITE/_site/catalog.json" <<'PY'
import json
import sys

node = next(n for n in json.load(open(sys.argv[1]))["nodes"] if n["id"] == "yano")
for score in node["scenario_scores"]:
    if score["configured"]:
        print(f'{score["id"]:<18} {score["passed"]:>5} / {score["total"]:<5} {score["percentage"]}%')
    else:
        print(f'{score["id"]:<18} no report')
print(f'{"overall":<18} {node["conformance"]["percentage"]}%')
PY
