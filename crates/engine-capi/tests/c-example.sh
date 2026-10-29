#!/usr/bin/env bash
# 用 cargo-c 把 msime-engine-capi 装进 target/engine-capi/stage，核对提交的头文件没有漂移，再用 CMake 按动态和静态两种方式编译 C 示例（非 Windows 再加一个同时链接系统 SQLite 的静态版本），各自在新生成的夹具上跑一遍。CI 的 engine-capi 作业在 Linux、macOS、Windows 上跑的就是它。
#
#   bash crates/engine-capi/tests/c-example.sh                  # 构建、核对头文件、跑示例
#   bash crates/engine-capi/tests/c-example.sh --update-header  # 改了公开接口之后：把生成的头文件写回 include/
#
# 需要 cargo-c（`cargo install cargo-c --locked`）、CMake、C 编译器和 Python 3；Linux 与 macOS 上还用 pkg-config。
set -euo pipefail

update_header=false
case "${1:-}" in
  "") ;;
  --update-header) update_header=true ;;
  *) echo "usage: $0 [--update-header]" >&2; exit 2 ;;
esac

root=$(cd "$(dirname "$0")/../../.." && pwd)
crate="$root/crates/engine-capi"
work="$root/target/engine-capi"
stage="$work/stage"

# Windows 的 Git Bash 里路径是 /d/a/...，交给 cargo、CMake 和示例程序之前换成 D:/a/... 的写法。
native() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}

python=${PYTHON:-$(command -v python3 || command -v python)}
windows=false
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) windows=true ;;
esac

rm -rf "$stage" "$work"/build-* "$work"/run-*
mkdir -p "$work"

# 调试构建：示例只核对接口和链接，不量速度。库名、SONAME、头文件和 .pc 都按 Cargo.toml 的 package.metadata.capi 生成。
cargo cinstall --locked --debug -p msime-engine-capi --prefix "$(native "$stage")" --libdir lib

generated="$stage/include/msime_engine.h"
committed="$crate/include/msime_engine.h"
if [[ "$update_header" == true ]]; then
  cp "$generated" "$committed"
  echo "updated $committed"
elif ! diff -u --strip-trailing-cr "$committed" "$generated"; then
  echo "include/msime_engine.h is stale: run bash crates/engine-capi/tests/c-example.sh --update-header and commit it" >&2
  exit 1
fi

"$python" "$crate/tests/fixture/make_fixture.py" "$(native "$work/resources")"

variants=(shared static)
if [[ "$windows" != true ]]; then
  variants+=(static-system-sqlite)
fi
for variant in "${variants[@]}"; do
  build="$work/build-$variant"
  link=${variant%%-*}
  system_sqlite=OFF
  [[ "$variant" == static-system-sqlite ]] && system_sqlite=ON
  cmake -S "$(native "$crate/examples/c")" -B "$(native "$build")" \
    -DCMAKE_BUILD_TYPE=Release \
    -DMSIME_ENGINE_PREFIX="$(native "$stage")" \
    -DMSIME_ENGINE_LINK="$link" \
    -DMSIME_EXAMPLE_SYSTEM_SQLITE="$system_sqlite"
  cmake --build "$(native "$build")" --config Release
  executable="$build/msime_engine_example"
  [[ -f "$build/Release/msime_engine_example.exe" ]] && executable="$build/Release/msime_engine_example.exe"
  [[ -f "$build/msime_engine_example.exe" ]] && executable="$build/msime_engine_example.exe"
  run="$work/run-$variant"
  mkdir -p "$run"
  echo "== $variant"
  "$executable" "$(native "$work/resources")" "$(native "$run/user")" "$(native "$run/cache")"
done
