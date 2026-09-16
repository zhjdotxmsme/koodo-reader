jest.mock("../assets/lib/kookit-extra-browser.min", () => ({
  KookitConfig: { ConvertLangMap: {} },
}));

// eslint-disable-next-line import/first
import { ocrEngineList } from "./dropdownList";

describe("ocrEngineList - 本地全功能模式", () => {
  it("不应再包含官方云端 OCR 引擎", () => {
    ocrEngineList.forEach((item) => {
      expect(item.value).not.toMatch(/^official-ai-/);
    });
  });

  it("应保留本地 OCR 引擎 system-ocr / paddle / tesseract", () => {
    const values = ocrEngineList.map((item) => item.value);
    expect(values).toContain("system-ocr");
    expect(values).toContain("paddle");
    expect(values).toContain("tesseract");
  });
});
