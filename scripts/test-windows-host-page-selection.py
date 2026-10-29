#!/usr/bin/env python3
"""编译实际 TIP 选词方法；替换窗口与编辑器，保留候选解析和选择逻辑。"""
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
header = Path("/usr/include/nlohmann/json.hpp")
if not header.exists() and not Path("/opt/homebrew/include/nlohmann/json.hpp").exists():
    print("skipped: nlohmann/json.hpp is unavailable")
    raise SystemExit(0)
text = (root / "platforms/windows/tsf/UI/CandidateListUIPresenter.cpp").read_text()
start = text.index("HRESULT CMetasequoiaIME::_SelectHighlightedHostCandidate(")
end = text.index("\n}\n", start) + 3
method = text[start:end]
support = r'''
#define MSIME_EDITION_FULL
#include "EngineSessionAdapter.h"
#include "CandidateSources.h"
#include "HostServerSelection.h"
#include <codecvt>
#include <cstdio>
#include <cstring>
#include <locale>
#include <limits>
#include <nlohmann/json.hpp>
using Json = nlohmann::json;
using HRESULT = int;
using TfEditCookie = int;
using WCHAR = wchar_t;
using UINT = unsigned;
struct ITfContext {};
#define _In_
#define _In_opt_
#define FAILED(value) ((value) < 0)
constexpr HRESULT S_OK = 0, S_FALSE = 1;
constexpr unsigned CP_UTF8 = 65001, WC_ERR_INVALID_CHARS = 1, MB_ERR_INVALID_CHARS = 1;
constexpr bool FALSE = false;
std::string narrow(const std::wstring &text) { return std::wstring_convert<std::codecvt_utf8<wchar_t>>{}.to_bytes(text); }
std::wstring wide(const std::string &text) { return std::wstring_convert<std::codecvt_utf8<wchar_t>>{}.from_bytes(text); }
int WideCharToMultiByte(unsigned, unsigned, const wchar_t *input, int length, char *output, int capacity, const char *, bool *) {
    const auto text = narrow(std::wstring(input, length));
    if (output && capacity >= static_cast<int>(text.size())) std::memcpy(output, text.data(), text.size());
    return static_cast<int>(text.size());
}
int MultiByteToWideChar(unsigned, unsigned, const char *input, int length, wchar_t *output, int capacity) {
    const auto text = wide(std::string(input, length));
    if (output && capacity >= static_cast<int>(text.size())) std::copy(text.begin(), text.end(), output);
    return static_cast<int>(text.size());
}
struct CStringRange {
    std::wstring text;
    void Set(const wchar_t *data, std::size_t length) { text.assign(data, length); }
    std::wstring ToWString() const { return text; }
};
struct CCandidateListItem {
    CStringRange _ItemString;
    std::size_t _EngineIndex = 999;
    bool MatchesEngineView(uint64_t session, uint64_t generation) const { return session == 7 && generation == 14; }
};
Json row(std::string text, std::size_t index, uint64_t generation = 14) {
    return Json{{"text", text}, {"source", 0}, {"highlighted", false},
                {"id", {{"session", 7}, {"generation", generation}, {"index", index}}}};
}
struct Host {
    Json current{{"session", 7}, {"generation", 14}, {"preedit", "shi"}, {"editing_text", "shi"},
                 {"candidates", Json::array({row("本地首项", 0), row("本地匹配", 7)})}};
    Json all{{"generation", 21}, {"candidates", Json::array({row("本地首项", 0, 21), row("后页第四项", 21, 21)})}};
    int selects = 0, any_selects = 0;
    bool all_ok = true, partial = false;
    bool valid() const { return true; }
    bool view(std::string *out, std::string *) { *out = Json{{"ok", true}, {"value", current}}.dump(); return true; }
    bool all_candidates(std::string *out, std::string *) { if (!all_ok) return false; *out = Json{{"ok", true}, {"value", all}}.dump(); return true; }
    bool choose(const Json &rows, uint64_t generation, std::size_t index, std::string *out) {
        for (const auto &item : rows) if (item.at("id").at("index") == index && item.at("id").at("generation") == generation) {
            Json after{{"session", 7}, {"generation", generation + 1}, {"preedit", partial ? "合成tail" : ""},
                       {"editing_text", partial ? "tail" : ""}, {"phrase_prefix", partial ? "合成" : ""}, {"candidates", Json::array()}};
            *out = Json{{"ok", true}, {"value", {{"handled", true}, {"commit", partial ? Json(nullptr) : item.at("text")}, {"view", after}}}}.dump();
            return true;
        }
        return false;
    }
    bool select(uint64_t generation, std::size_t index, std::string *out, std::string *) { ++selects; return choose(current.at("candidates"), generation, index, out); }
    bool select_any(uint64_t generation, std::size_t index, std::string *out, std::string *) { ++any_selects; return choose(all.at("candidates"), generation, index, out); }
};
struct Engine { Host *host; Host *GetHostEngineAdapter() { return host; } };
struct Presenter {
    bool _GetSelectedEngineCandidate(CCandidateListItem *out) { out->_ItemString.text = L"本地首项"; return true; }
};
struct CMetasequoiaIME {
    Engine *_pCompositionProcessorEngine;
    Presenter *_pCandidateListUIPresenter;
    std::wstring committed;
    int completed = 0, refreshed = 0;
    wchar_t _smartPunctuationShadowChar = 0;
    bool _smartPunctuationShadowValid = false;
    HRESULT _SelectHighlightedHostCandidate(TfEditCookie, ITfContext *, UINT, const std::wstring *);
    wchar_t _CandidateCommitPairedClosing(const std::wstring &) { return 0; }
    HRESULT _AddCharAndFinalize(TfEditCookie, ITfContext *, const CStringRange *text) { committed = text->text; return S_OK; }
    void _DeleteCandidateList(bool, ITfContext *) {}
    void _HandleCompleteCommitFirst(TfEditCookie, ITfContext *) { ++completed; }
    void _OpenCandidateCommitPair(wchar_t, wchar_t) {}
    HRESULT _HandleCompositionInputWorker(Engine *, TfEditCookie, ITfContext *, uint64_t) { ++refreshed; return S_OK; }
};
'''
tests = r'''
int main() {
    int failed = 0;
    const auto check = [&](const char *name, Host host, const std::wstring &choice, HRESULT expected,
                           const std::wstring &commit, int any_calls, int refresh = 0) {
        Engine engine{&host}; Presenter presenter; CMetasequoiaIME ime{&engine, &presenter, {}}; ITfContext context;
        const auto status = ime._SelectHighlightedHostCandidate(0, &context, choice.find(L'\t') == std::wstring::npos
            ? FanyImeReplyType::Normal : FanyImeReplyType::NeedToCreateWord, &choice);
        const bool ok = status == expected && ime.committed == commit && host.any_selects == any_calls && ime.refreshed == refresh;
        std::printf("%s status=%d expected=%d commit_matches=%d any_calls=%d match=%d\n", name, status, expected, ime.committed == commit, host.any_selects, ok);
        failed += !ok;
    };
    check("same_text_different_index", Host{}, L"本地匹配", S_OK, L"本地匹配", 0);
    check("page_three_slot_four", Host{}, L"后页第四项", S_OK, L"后页第四项", 1);
    Host duplicate;
    duplicate.all["candidates"].push_back(row("后页第四项", 22, 21));
    check("ambiguous_full_list_rejected", duplicate, L"后页第四项", S_FALSE, L"", 0);
    Host missing;
    check("unknown_text_rejected", missing, L"不存在的候选", S_FALSE, L"", 0);
    Host unavailable; unavailable.all_ok = false;
    check("full_list_failure_rejected", unavailable, L"后页第四项", S_FALSE, L"", 0);
    Host partial; partial.partial = true;
    check("page_three_partial_continues", partial, L"后页第四项", S_OK, L"", 1, 1);
    Host wire; wire.partial = true; wire.current["phrase_prefix"] = "合成";
    check("partial_wire_extracts_new_word", wire, L"tail\t合成后页第四项\t合成后页第四项tail", S_OK, L"", 1, 1);
    Host held; held.current["phrase_prefix"] = "合成";
    check("full_commit_removes_held_prefix", held, L"合成后页第四项", S_OK, L"后页第四项", 1);
    std::printf("host page selection: %d failures\n", failed);
    return failed ? 1 : 0;
}
'''
with tempfile.TemporaryDirectory(prefix="msime-host-page-") as directory:
    cpp = Path(directory) / "probe.cpp"
    binary = Path(directory) / "probe"
    cpp.write_text(support + method + tests)
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
                    "-I" + str(root / "platforms/windows/tsf"), "-I" + str(root / "platforms/windows/common"),
                    "-I" + str(root / "crates/host-api/include"), "-I/opt/homebrew/include",
                    str(cpp), str(root / "platforms/windows/tsf/EngineResponse.cpp"), "-o", str(binary)], check=True)
    raise SystemExit(subprocess.run([str(binary)]).returncode)
