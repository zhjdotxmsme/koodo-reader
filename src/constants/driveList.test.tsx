import { driveList } from "./driveList";

describe("driveList - 取消免费版限制", () => {
  it("云盘列表不应存在 Pro 限制标记", () => {
    expect(driveList.length).toBeGreaterThan(0);
    driveList.forEach((item) => {
      expect(item.isPro).toBe(false);
    });
  });

  it("云盘列表条目应保持 value 唯一", () => {
    const values = driveList.map((item) => item.value);
    expect(new Set(values).size).toBe(values.length);
  });
});
