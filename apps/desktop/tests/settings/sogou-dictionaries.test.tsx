// @vitest-environment jsdom
import { afterEach, expect, test, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { SogouDictionaries } from "../../../../packages/ui/src/settings/sogou-dictionaries";
import type {
  SogouAction,
  SogouStatus,
} from "../../../../packages/ui/src/dictionary/dictionary-contracts";

const ready: SogouStatus = {
  groups: [{ id: 97, name: "计算机" }],
  settings: {
    groups: [],
    dictionaries: [],
    automatic: true,
    phase: "idle",
    error: null,
    limited: false,
  },
  installed: [],
  max_dictionaries: 32,
};
afterEach(cleanup);

test("初始化不下载，兴趣选择保存到共享服务", async () => {
  const request = vi.fn(async () => ready);
  render(<SogouDictionaries request={request} compact />);
  expect(await screen.findByRole("switch", { name: "计算机" })).toBeTruthy();
  expect(request.mock.calls).toEqual([[{ command: "status" }]]);
  fireEvent.click(screen.getByRole("switch", { name: "计算机" }));
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith({
      command: "configure",
      groups: [97],
      dictionaries: [],
      automatic: true,
    }),
  );
});

test("精确下载、停用和移除使用各自编号", async () => {
  const installed = {
    ...ready,
    installed: [{ id: 101, name: "合成词库", enabled: true, entries: 3 }],
  };
  const request = vi.fn(async () => installed);
  render(<SogouDictionaries request={request} />);
  await screen.findByRole("switch", { name: "计算机" });
  fireEvent.change(screen.getByRole("textbox", { name: "搜狗词库编号" }), {
    target: { value: "101" },
  });
  fireEvent.click(screen.getByRole("button", { name: "下载" }));
  await waitFor(() => expect(request).toHaveBeenCalledWith({ command: "install", id: 101 }));
  await waitFor(() =>
    expect(screen.getByRole("switch", { name: "合成词库 · 3 词" }).hasAttribute("disabled")).toBe(
      false,
    ),
  );
  fireEvent.click(screen.getByRole("switch", { name: "合成词库 · 3 词" }));
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith({ command: "set_enabled", id: 101, enabled: false }),
  );
  await act(async () => {});
  fireEvent.click(screen.getByRole("button", { name: "移除" }));
  await waitFor(() => expect(request).toHaveBeenCalledWith({ command: "remove", id: 101 }));
});

test("目录失败保持原页，成功分页采用服务返回的页码", async () => {
  let fail = false;
  const request = vi.fn(async (action: SogouAction) => {
    if (action.command === "catalog") {
      if (fail) throw new Error("fixture failure");
      return {
        group: 97,
        page: action.page,
        has_more: true,
        entries: [{ id: 101, name: "合成词库" }],
      };
    }
    return ready;
  });
  render(<SogouDictionaries request={request} />);
  await screen.findByRole("switch", { name: "计算机" });
  fireEvent.click(screen.getByRole("button", { name: "浏览" }));
  await screen.findByText("第 1 页");
  fail = true;
  fireEvent.click(screen.getByRole("button", { name: "下一页" }));
  await screen.findByRole("alert");
  expect(screen.getByText("第 1 页")).toBeTruthy();
  fail = false;
  fireEvent.click(screen.getByRole("button", { name: "下一页" }));
  await screen.findByText("第 2 页");
});

test("切换宿主时丢弃旧响应，并恢复新宿主的控件", async () => {
  let finish: (value: SogouStatus) => void = () => {};
  const oldRequest = vi.fn(
    () =>
      new Promise<SogouStatus>((resolve) => {
        finish = resolve;
      }),
  );
  const view = render(<SogouDictionaries request={oldRequest} compact />);
  const newRequest = vi.fn(async () => ready);
  view.rerender(<SogouDictionaries request={newRequest} compact />);
  await screen.findByRole("switch", { name: "计算机" });
  await act(async () => finish({ ...ready, groups: [{ id: 1, name: "旧宿主" }] }));
  expect(screen.queryByText("旧宿主")).toBeNull();
  expect(screen.getByRole("switch", { name: "计算机" }).hasAttribute("disabled")).toBe(false);
});
