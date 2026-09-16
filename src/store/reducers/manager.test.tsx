import { manager } from "./manager";

describe("manager reducer - 本地全功能模式", () => {
  it("初始 isAuthed 应为 true（不再依赖登录令牌）", () => {
    const state = manager(undefined, {
      type: "@@INIT",
      payload: undefined,
    } as any);
    expect(state.isAuthed).toBe(true);
  });

  it("HANDLE_AUTHED 派发后保持 true", () => {
    const state = manager(undefined, {
      type: "HANDLE_AUTHED",
      payload: true,
    } as any);
    expect(state.isAuthed).toBe(true);
  });
});
