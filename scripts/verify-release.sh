#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

corepack enable
yarn install --immutable
yarn check
yarn prepare

test -f lib/module/index.js
test -f lib/typescript/src/index.d.ts

npm pack --dry-run

echo "v0.1 release verification passed"
