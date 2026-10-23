#!/usr/bin/env bash
# ADR-060: fetches the pinned node-diversity corpora (corpora.env) into <dir>, for
#   ./gradlew :ledger-conformance:nodeDiversityReports -PnodeDiversityCorpora=<dir>
#
# <dir>/node-diversity-conformance  pragma's repository at NDC_REVISION (the ledger-rules fixtures)
# <dir>/cardano-cbor-dataset        the CBOR dataset at CBOR_DATASET_REVISION
# <dir>/uplc-conformance            the UPLC vectors, checked against UPLC_CORPUS_SHA256
# <dir>/turbo                       the turbo scripts, checked against TURBO_CORPUS_SHA256
#
# Idempotent: a repository already at its pin and a tarball already extracted are kept.
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "usage: $0 <dir>" >&2
    exit 2
fi
DIR=$1
# shellcheck source=corpora.env
source "$(dirname "$0")/corpora.env"
mkdir -p "$DIR"

checkout() {
    local repository=$1 revision=$2 target=$3
    if [[ "$(git -C "$target" rev-parse HEAD 2>/dev/null)" == "$revision" ]]; then
        echo "$target: at $revision"
        return
    fi
    rm -rf "$target"
    git init -q "$target"
    git -C "$target" fetch -q --depth 1 "$repository" "$revision"
    git -C "$target" checkout -q FETCH_HEAD
    echo "$target: checked out $revision"
}

sha256() {
    if command -v sha256sum >/dev/null; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

extract() {
    local url=$1 digest=$2 target=$3
    if [[ -f "$target/.sha256" && "$(cat "$target/.sha256")" == "$digest" ]]; then
        echo "$target: extracted ($digest)"
        return
    fi
    local archive
    archive=$(mktemp)
    curl --fail --silent --show-error --location --retry 3 -o "$archive" "$url"
    local actual
    actual=$(sha256 "$archive")
    if [[ "$actual" != "$digest" ]]; then
        echo "$url: sha256 $actual, expected $digest" >&2
        rm -f "$archive"
        exit 1
    fi
    rm -rf "$target"
    mkdir -p "$target"
    zstd -dcq "$archive" | tar -x -C "$target"
    rm -f "$archive"
    echo "$digest" > "$target/.sha256"
    echo "$target: extracted $url"
}

checkout "$NDC_REPOSITORY" "$NDC_REVISION" "$DIR/node-diversity-conformance"
checkout "$CBOR_DATASET_REPOSITORY" "$CBOR_DATASET_REVISION" "$DIR/cardano-cbor-dataset"
extract "$UPLC_CORPUS_URL" "$UPLC_CORPUS_SHA256" "$DIR/uplc-conformance"
extract "$TURBO_CORPUS_URL" "$TURBO_CORPUS_SHA256" "$DIR/turbo"
