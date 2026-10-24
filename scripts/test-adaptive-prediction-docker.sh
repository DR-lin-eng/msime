#!/usr/bin/env bash
# 固定版本的 Docker 回归；以普通用户运行文件权限和学习边界测试。
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="msime-adaptive-prediction-test:20261021"
docker build -f "$root/scripts/docker/Dockerfile.adaptive-prediction" -t "$image" "$root/scripts/docker"
mkdir -p "$root/target"
docker run --rm \
  --mount "type=bind,src=$root,dst=/source" \
  --mount type=volume,src=msime-adaptive-cargo,dst=/usr/local/cargo \
  --workdir /source --env CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-4}" \
  --env CARGO_NET_GIT_FETCH_WITH_CLI=true "$image" bash -euo pipefail -c '
    cargo fmt --all --check
    python3 scripts/generate_adaptive_model.py --check
    python3 -c "import onnx; onnx.checker.check_model(onnx.load(\"crates/engine/src/adaptive/model.onnx\")); print(\"ONNX checker: passed\")"
    runtime="$(python3 -c "import pathlib,onnxruntime; print(next((pathlib.Path(onnxruntime.__file__).parent / \"capi\").glob(\"libonnxruntime.so.*\")))")"
    export MSIME_ONNX_RUNTIME="$runtime" MSIME_TEST_ONNX_RUNTIME="$runtime"
    chmod -R a+rwX /usr/local/cargo /source/target
    runuser -u nobody -- cargo test --locked -p msime-engine -p msime-input-runtime
    runuser -u nobody -- cargo clippy --locked -p msime-engine -p msime-input-runtime --all-targets -- -D warnings
    MSIME_ONNX_RUNTIME=/missing/libonnxruntime.so MSIME_TEST_ONNX_RUNTIME= runuser -u nobody -- cargo test --locked -p msime-engine --test adaptive_transaction_probe -- --nocapture
    runuser -u nobody -- cargo check --locked -p msime-engine --no-default-features
  '
