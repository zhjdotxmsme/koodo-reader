import JSZip from "jszip";
import { restoreFromBrowser } from "./restore";

const mockStore: Record<string, string> = {};

jest.mock("../../assets/lib/kookit-extra-browser.min", () => ({
  CommonTool: {
    arrayBufferToBase64: jest.fn(),
  },
  ConfigService: {
    getItem: (key: string) => mockStore[key] ?? null,
    setItem: (key: string, value: string) => {
      mockStore[key] = value;
    },
  },
}));

jest.mock("react-hot-toast", () => ({
  loading: jest.fn(),
  error: jest.fn(),
}));

jest.mock("../../i18n", () => ({
  t: (key: string) => key,
}));

jest.mock("../common", () => ({
  generateSyncRecord: jest.fn(),
  getStorageLocation: jest.fn(() => ""),
}));

jest.mock("./common", () => ({
  upgradeConfig: jest.fn(),
  upgradeStorage: jest.fn(),
}));

jest.mock("localforage", () => ({
  setItem: jest.fn(),
}));

jest.mock("./sqlUtil", () => jest.fn());
jest.mock("../storage/databaseService", () => ({
  getAllRecords: jest.fn(),
  saveAllRecords: jest.fn(),
}));
jest.mock("./localFile", () => ({
  LocalFileManager: { saveFile: jest.fn() },
}));
jest.mock("./coverUtil", () => ({ saveCover: jest.fn() }));

describe("Android backup restore", () => {
  beforeEach(() => {
    Object.keys(mockStore).forEach((key) => delete mockStore[key]);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("merges Android CSS into existing desktop readerConfig", async () => {
    mockStore.readerConfig = JSON.stringify({
      fontSize: "20",
      theme: "dark",
      customBookCSS: "body{color:black}",
      isCustomBookCSS: "no",
    });
    const zip = new JSZip();
    zip.file(
      "config/config.json",
      JSON.stringify({
        textRuleList: "[]",
        textRules: "{}",
        readerConfig: JSON.stringify({
          customBookCSS: "h1{color:red}",
          isCustomBookCSS: "yes",
        }),
      })
    );
    zip.file(
      "config/koodo-android.json",
      JSON.stringify({ version: 1, feature: {}, gestures: {} })
    );
    const buffer = await zip.generateAsync({ type: "arraybuffer" });
    jest.spyOn(HTMLInputElement.prototype, "click").mockImplementation(function (
      this: HTMLInputElement
    ) {
      const file = { arrayBuffer: async () => buffer };
      this.onchange?.({ target: { files: [file] } } as unknown as Event);
    });

    await expect(restoreFromBrowser()).resolves.toBe("success");

    expect(JSON.parse(mockStore.readerConfig)).toEqual({
      fontSize: "20",
      theme: "dark",
      customBookCSS: "h1{color:red}",
      isCustomBookCSS: "yes",
    });
  });
});
