import JSZip from "jszip";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import { restoreFromBrowser, restoreFromfilePath } from "./restore";

const { restoreFromPath } = require("../main/backup-util");
const { TextDecoder } = require("util");
(global as any).TextDecoder = TextDecoder;

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

const writeElectronBackup = async (isAndroidBackup: boolean) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "koodo-restore-test-"));
  const filePath = path.join(directory, "backup.zip");
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
  if (isAndroidBackup) {
    zip.file(
      "config/koodo-android.json",
      JSON.stringify({ version: 1, feature: {}, gestures: {} })
    );
  }
  fs.writeFileSync(filePath, await zip.generateAsync({ type: "nodebuffer" }));
  return { directory, filePath };
};

const restoreElectronBackup = async (filePath: string) => {
  const { getStorageLocation } = require("../common");
  getStorageLocation.mockReturnValue(path.dirname(filePath));
  (window as any).electronAPI = {
    fs,
    path,
    on: jest.fn(),
    removeListener: jest.fn(),
    invoke: jest.fn(async (channel: string, config: any) => {
      if (channel === "restore-path") {
        return restoreFromPath(config, jest.fn());
      }
      throw new Error(`Unexpected IPC channel: ${channel}`);
    }),
  };
  return restoreFromfilePath(filePath);
};

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

  it("preserves unrelated desktop readerConfig keys through the Electron boundary", async () => {
    mockStore.readerConfig = JSON.stringify({
      fontSize: "20",
      theme: "dark",
      customBookCSS: "body{color:black}",
      isCustomBookCSS: "no",
    });
    const backup = await writeElectronBackup(true);
    try {
      await expect(restoreElectronBackup(backup.filePath)).resolves.toBe(true);

      expect(JSON.parse(mockStore.readerConfig)).toEqual({
        fontSize: "20",
        theme: "dark",
        customBookCSS: "h1{color:red}",
        isCustomBookCSS: "yes",
      });
      expect(mockStore["koodo-android.json"]).toBeUndefined();
    } finally {
      fs.unlinkSync(backup.filePath);
      (fs as any).rmSync(backup.directory, { recursive: true });
    }
  });

  it("keeps replacement semantics for non-Android Electron backups", async () => {
    mockStore.readerConfig = JSON.stringify({
      fontSize: "20",
      theme: "dark",
      customBookCSS: "body{color:black}",
      isCustomBookCSS: "no",
    });
    const backup = await writeElectronBackup(false);
    try {
      await expect(restoreElectronBackup(backup.filePath)).resolves.toBe(true);

      expect(JSON.parse(mockStore.readerConfig)).toEqual({
        customBookCSS: "h1{color:red}",
        isCustomBookCSS: "yes",
      });
    } finally {
      fs.unlinkSync(backup.filePath);
      (fs as any).rmSync(backup.directory, { recursive: true });
    }
  });
});
