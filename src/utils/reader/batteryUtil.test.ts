import { formatBattery, getBatteryLevel } from "./batteryUtil";

function mockGetBattery(level: number) {
  Object.defineProperty(window.navigator, "getBattery", {
    value: jest.fn().mockResolvedValue({ level, charging: true }),
    configurable: true,
    writable: true,
  });
}

describe("getBatteryLevel (A9)", () => {
  afterEach(() => {
    delete (window.navigator as any).getBattery;
  });

  it("resolves the percent level when supported", async () => {
    mockGetBattery(0.85);
    await expect(getBatteryLevel(50)).resolves.toBe(85);
    mockGetBattery(1);
    await expect(getBatteryLevel(50)).resolves.toBe(100);
  });

  it("resolves null when the API is missing", async () => {
    delete (window.navigator as any).getBattery;
    await expect(getBatteryLevel(20)).resolves.toBeNull();
  });

  it("resolves null when the promise rejects", async () => {
    Object.defineProperty(window.navigator, "getBattery", {
      value: jest.fn().mockRejectedValue(new Error("nope")),
      configurable: true,
      writable: true,
    });
    await expect(getBatteryLevel(20)).resolves.toBeNull();
  });

  it("resolves null when no level arrives before the timeout", async () => {
    Object.defineProperty(window.navigator, "getBattery", {
      value: jest.fn().mockResolvedValue({ level: undefined, charging: false }),
      configurable: true,
      writable: true,
    });
    await expect(getBatteryLevel(30)).resolves.toBeNull();
  });
});

describe("formatBattery (A9)", () => {
  it("formats valid percents", () => {
    expect(formatBattery(85)).toBe("85%");
    expect(formatBattery(0)).toBe("0%");
    expect(formatBattery(100)).toBe("100%");
  });

  it("returns empty for invalid values", () => {
    expect(formatBattery(null as any)).toBe("");
    expect(formatBattery(-1)).toBe("");
    expect(formatBattery(101)).toBe("");
    expect(formatBattery(NaN)).toBe("");
  });
});
