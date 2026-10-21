#!/usr/bin/env bash
# Replaces the vendored cardano-blueprint vectors with the contents of a vectors.tar.gz (URL or local path) and
# prints the values to pin. See README.md for the rest of the update.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <vectors.tar.gz URL or path>" >&2
  exit 2
fi

here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if [[ "$1" =~ ^https?:// ]]; then
  curl -fsSL -o "$work/vectors.tar.gz" "$1"
else
  cp "$1" "$work/vectors.tar.gz"
fi

mkdir "$work/extracted"
tar -xzf "$work/vectors.tar.gz" -C "$work/extracted"
if [[ ! -d "$work/extracted/eras" || ! -d "$work/extracted/pparams" ]]; then
  echo "error: the tarball has no eras/ and pparams/ at its root" >&2
  exit 1
fi

rm -rf "$here/eras" "$here/pparams"
cp -R "$work/extracted/eras" "$work/extracted/pparams" "$here/"

python3 - "$here" "$work/vectors.tar.gz" <<'EOF'
import hashlib, os, sys

root, tarball = sys.argv[1], sys.argv[2]
files = []
for directory, _, names in os.walk(root):
    for name in names:
        rel = os.path.relpath(os.path.join(directory, name), root)
        if rel.startswith(("eras/", "pparams/")):
            files.append(rel)
corpus = hashlib.sha256()
for rel in sorted(files):
    with open(os.path.join(root, rel), "rb") as f:
        corpus.update(f"{rel}\n{hashlib.sha256(f.read()).hexdigest()}\n".encode())
with open(tarball, "rb") as f:
    print("tarball sha256 :", hashlib.sha256(f.read()).hexdigest())
print("corpus digest  :", corpus.hexdigest())
print("vectors        :", sum(1 for rel in files if rel.startswith("eras/")))
print("pparams        :", sum(1 for rel in files if rel.startswith("pparams/")))
EOF
