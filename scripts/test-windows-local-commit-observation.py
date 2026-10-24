#!/usr/bin/env python3
"""编译实际回车校验构造代码，覆盖宿主拥有组字时旧缓冲为空的情况。"""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent
compiler = shutil.which("c++")
if not compiler:
    print("skipped: no C++ compiler")
    raise SystemExit(0)
source = (root / "platforms/windows/tsf/Key/KeyEventSink.cpp").read_text()
start = source.index("std::wstring localCommitObservation;")
end = source.index("// CandidateActive", start)
body = source[start:end]
preamble = r'''
#include <cstdio>
#include <string>
#include <vector>
constexpr unsigned VK_RETURN = 13;
namespace GlobalIme { std::wstring word_for_creating_word; }
struct CStringRange {
    std::wstring text;
    const wchar_t *Get() const { return text.empty() ? nullptr : text.data(); }
    std::size_t GetLength() const { return text.size(); }
};
struct State { std::wstring rawInput; };
struct Engine {
    CStringRange legacy;
    State state;
    const CStringRange &GetKeystrokeBuffer() const { return legacy; }
    State GetKeyCompositionState() const { return state; }
};
std::wstring observe(Engine *engine, const std::wstring &prefix, unsigned code = VK_RETURN) {
    auto *_pCompositionProcessorEngine = engine;
    GlobalIme::word_for_creating_word = prefix;
    const auto _IsComposing = [&] { return engine && (!engine->state.rawInput.empty() || !prefix.empty()); };
'''
tests = r'''
    return localCommitObservation;
}
int main() {
    int failed = 0;
    const auto check = [&](const char *name, const std::wstring &raw,
                           const std::wstring &legacy, const std::wstring &prefix,
                           const std::wstring &expected) {
        Engine engine{{legacy}, {raw}};
        auto actual = observe(&engine, prefix);
        std::printf("%s observed_units=%zu expected_units=%zu match=%d\n", name,
                    actual.size(), expected.size(), actual == expected);
        failed += actual != expected;
    };
    check("host_english_empty_legacy", L"syntheticword", L"", L"", L"syntheticword");
    check("host_pinyin_empty_legacy", L"nihao", L"", L"", L"nihao");
    check("host_partial_prefix", L"ceshi", L"", L"合成", L"合成ceshi");
    check("host_replaces_stale_legacy", L"nihao", L"old", L"", L"nihao");
    check("legacy_same_state", L"nihao", L"nihao", L"", L"nihao");
    check("bounded_observation", std::wstring(128, L'a'), L"", L"", L"");
    std::printf("local commit observation: %d failures\n", failed);
    return failed ? 1 : 0;
}
'''
with tempfile.TemporaryDirectory(prefix="msime-local-commit-") as directory:
    cpp = Path(directory) / "probe.cpp"
    binary = Path(directory) / "probe"
    cpp.write_text(preamble + body + tests)
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", str(cpp), "-o", str(binary)], check=True)
    raise SystemExit(subprocess.run([str(binary)]).returncode)
