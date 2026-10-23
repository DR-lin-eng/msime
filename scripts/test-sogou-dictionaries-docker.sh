#!/usr/bin/env bash
# 固定 Rust 版本，普通用户下验证词库下载、替换和持久化；联网用例需显式开启。
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="msime-sogou-dictionaries-test:20261023"
docker build -f "$root/scripts/docker/Dockerfile.sogou-dictionaries" -t "$image" "$root/scripts/docker"
mkdir -p "$root/target"
docker run --rm \
  --mount "type=bind,src=$root,dst=/source" \
  --mount type=volume,src=msime-sogou-cargo,dst=/usr/local/cargo \
  --workdir /source --env CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-3}" \
  --env MSIME_SOGOU_LIVE="${MSIME_SOGOU_LIVE:-0}" "$image" bash -euo pipefail -c '
    cargo fmt --all --check
    chmod -R a+rwX /usr/local/cargo /source/target
    runuser -u nobody -- cargo test --locked -p msime-client-services --lib dictionary::
    runuser -u nobody -- cargo test --locked -p msime-host-api --lib sogou
    runuser -u nobody -- cargo test --locked -p msime-host-api --lib scoped_collection
    runuser -u nobody -- cargo clippy --locked -p msime-client-services -p msime-host-api --lib -- -D warnings
    if [[ "$MSIME_SOGOU_LIVE" == 1 ]]; then
      runuser -u nobody -- cargo test --locked -p msime-client-services --lib sogou_live_official_catalog_and_scel_install -- --ignored --nocapture
    fi
  '
