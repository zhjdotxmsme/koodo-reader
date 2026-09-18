import React from "react";
import "./header.css";
import SearchBox from "../../components/searchBox";
import ImportLocal from "../../components/importLocal";
import { HeaderProps, HeaderState } from "./interface";
import {
  ConfigService,
  KookitConfig,
  KOReaderUtil,
} from "../../assets/lib/kookit-extra-browser.min";
import UpdateInfo from "../../components/dialogs/updateDialog";
import { generateSnapshot } from "../../utils/file/backup";
import { isElectron } from "react-device-detect";
import { upgradeConfig, upgradeStorage } from "../../utils/file/common";
import toast from "react-hot-toast";
import { Trans } from "react-i18next";
import DatabaseService from "../../utils/storage/databaseService";
import BookUtil from "../../utils/file/bookUtil";
import {
  getBookPartialMd5,
  getWebsiteUrl,
  openInBrowser,
  scanFolderForNewBooks,
  throttle,
  AUTO_IMPORT_FOLDERS_KEY,
} from "../../utils/common";
import { LocalFileManager } from "../../utils/file/localFile";
import packageJson from "../../../package.json";
import TokenService from "../../utils/storage/tokenService";
declare var window: any;

class Header extends React.Component<HeaderProps, HeaderState> {
  private hasRunAutoImport: boolean = false;
  private resizeHandler: (() => void) | null = null;
  private koReaderSyncListener: (() => void) | null = null;
  constructor(props: HeaderProps) {
    super(props);

    this.state = {
      isOnlyLocal: false,
      language: ConfigService.getReaderConfig("lang"),
      isNewVersion: false,
      width: document.body.clientWidth,
      isHidePro: false,
      notificationCount: 0,
    };
  }
  async componentDidMount() {
    if (isElectron) {
      try {
        await generateSnapshot();
      } catch (error) {
        console.error("Failed to generate snapshot:", error);
      }
    }
    this.props.handleFetchAuthed();
    this.props.handleFetchDefaultSyncOption();
    this.props.handleFetchDataSourceList();
    if (isElectron) {
      const fs = window.electronAPI.fs;
      const path = window.electronAPI.path;
      const ipcRenderer = window.electronAPI;
      const dirPath = ipcRenderer.sendSync("user-data", "ping");
      if (!fs.existsSync(dirPath)) {
        fs.mkdirSync(path.join(dirPath, "data", "book"), { recursive: true });
      }

      if (
        ConfigService.getReaderConfig("storageLocation") &&
        !ConfigService.getItem("storageLocation")
      ) {
        ConfigService.setItem(
          "storageLocation",
          ConfigService.getReaderConfig("storageLocation")
        );
      }
      if (ConfigService.getReaderConfig("isHidePro") === "yes") {
        this.setState({ isHidePro: true });
      }

      //Check for data update
      //upgrade data from old version
      let res1 = await upgradeStorage(this.handleFinishUpgrade);
      let res2 = await upgradeConfig();
      if (!res1 || !res2) {
        console.error("upgrade failed");
      }

      ipcRenderer.on("open-book-from-link", async (config: any) => {
        const book = await DatabaseService.getRecord(config.bookKey, "books");
        if (book) {
          BookUtil.redirectBook(book);
        }
      });
      ipcRenderer.on("open-note-from-link", async (config: any) => {
        const note = await DatabaseService.getRecord(config.noteKey, "notes");
        if (!note) return;
        const book = await DatabaseService.getRecord(note.bookKey, "books");
        if (!book) return;
        let bookLocation: any = {};
        try {
          bookLocation = JSON.parse(note.cfi) || {};
        } catch (error) {
          bookLocation.cfi = note.cfi;
          bookLocation.chapterTitle = note.chapter;
        }
        if (bookLocation.fingerprint) {
          bookLocation.chapterDocIndex = bookLocation.page - 1 + "";
          bookLocation.chapterHref = "title" + (bookLocation.page - 1);
        }
        ConfigService.setObjectConfig(
          note.bookKey,
          bookLocation,
          "recordLocation"
        );
        BookUtil.redirectBook(book);
      });
      ipcRenderer.on("chat-message", async (msg: any) => {
        if (msg.payload.event === "new-message") {
          ConfigService.setReaderConfig("isAllowNotification", "yes");
        }
      });
    } else {
      await upgradeConfig();
      const status = await LocalFileManager.getPermissionStatus();
      if (
        !ConfigService.getItem("isUseLocal") &&
        LocalFileManager.isSupported()
      ) {
        this.props.handleLocalFileDialog(true);
      } else if (
        ConfigService.getItem("isUseLocal") === "yes" &&
        !status.directoryName
      ) {
        this.props.handleLocalFileDialog(true);
      } else if (
        ConfigService.getItem("isUseLocal") === "yes" &&
        (status.needsReauthorization || !status.hasAccess)
      ) {
        this.props.handleLocalFileDialog(true);
      }
    }
    this.resizeHandler = throttle(() => {
      this.setState({ width: document.body.clientWidth });
    });
    window.addEventListener("resize", this.resizeHandler);
    this.handleOpenLastReadBook();
    this.autoScanFoldersOnStart();
    // 本地全功能模式：云端同步已移除；KOReader 为局域网设备集成，按需执行
    this.koReaderSyncListener = () => this.handleKOReaderSync();
    window.addEventListener("koodo-koreader-sync", this.koReaderSyncListener);
    if (ConfigService.getReaderConfig("isEnableKoReaderSync") === "yes") {
      this.handleKOReaderSync();
    }
  }
  componentWillUnmount() {
    if (this.resizeHandler) {
      window.removeEventListener("resize", this.resizeHandler);
      this.resizeHandler = null;
    }
    if (this.koReaderSyncListener) {
      window.removeEventListener("koodo-koreader-sync", this.koReaderSyncListener);
      this.koReaderSyncListener = null;
    }
  }
  autoScanFoldersOnStart = async () => {
    if (!isElectron || this.hasRunAutoImport) return;
    this.hasRunAutoImport = true;
    try {
      const folders =
        ConfigService.getAllListConfig(AUTO_IMPORT_FOLDERS_KEY) || [];
      if (folders.length === 0) return;
      const importBookFunc = this.props.importBookFunc;
      if (!importBookFunc) {
        console.error("Auto import: no import function available");
        return;
      }
      for (const folderPath of folders) {
        await scanFolderForNewBooks(folderPath, importBookFunc);
      }
    } catch (error) {
      console.error("Auto import folder scan error:", error);
    }
  };
  handleOpenLastReadBook = async () => {
    let filePath = "";
    //open book when app start
    if (isElectron) {
      const ipcRenderer = window.electronAPI;
      filePath = ipcRenderer.sendSync("check-file-data");
    }
    if (
      ConfigService.getReaderConfig("isOpenBook") === "yes" &&
      !this.props.currentBook.key &&
      !filePath
    ) {
      let lastReadBookKey = ConfigService.getAllListConfig("recentBooks")[0];
      if (lastReadBookKey) {
        let fullBook = await DatabaseService.getRecord(
          lastReadBookKey,
          "books"
        );
        if (fullBook) {
          this.props.handleReadingBook(fullBook);
          BookUtil.redirectBook(fullBook);
        }
      }
    }
  };
  handleFinishUpgrade = () => {
    this.props.handleFetchBooks();
    setTimeout(() => {
      if (this.props.mode === "home") {
        this.props.history.push("/manager/home");
      }
    }, 2000);
  };

  handleKOReaderSync = async () => {
    if (ConfigService.getReaderConfig("isEnableKoReaderSync") !== "yes") {
      return;
    }

    toast.loading(this.props.t("Start syncing") + " (KOReader)", {
      id: "koreader-sync",
      position: "bottom-center",
    });
    try {
      const koReaderUtil = new KOReaderUtil(
        ConfigService,
        TokenService,
        DatabaseService
      );
      const summary =
        await koReaderUtil.syncKOReaderProgress(getBookPartialMd5);
      if (summary.pulledBooks > 0 || summary.pushedBooks > 0) {
        this.props.handleFetchBooks();
      }
      toast.success(
        this.props.t("Synchronisation successful") + " (KOReader)",
        {
          id: "koreader-sync",
        }
      );
    } catch (error) {
      console.error(error);
      toast.error(
        this.props.t("Sync failed") +
          " (KOReader): " +
          (error instanceof Error ? error.message : String(error)),
        {
          id: "koreader-sync",
          duration: 6000,
        }
      );
    }
  };

  render() {
    return (
      <div
        className="header"
        style={this.props.isCollapsed ? { marginLeft: "40px" } : {}}
      >
        <div
          className="mobile-menu-btn"
          role="button"
          aria-label={this.props.t("Menu")}
          onClick={() => {
            if (this.props.handleMobileMenu) {
              this.props.handleMobileMenu();
            }
          }}
        >
          <span className="icon-menu"></span>
        </div>
        {this.props.isAuthed && (
          <div
            className="header-chat-widget"
            onClick={async () => {
              this.setState({ notificationCount: 0 });
              let deviceUuid = await TokenService.getFingerprint();
              let url =
                getWebsiteUrl() +
                (ConfigService.getReaderConfig("lang").startsWith("zh")
                  ? "/zh/faq"
                  : "/en/faq") +
                "?referer=app&version=" +
                packageJson.version +
                "&client=web&device=" +
                deviceUuid;
              if (isElectron) {
                window.electronAPI?.invoke("new-chat", {
                  url: url,
                });
              } else {
                openInBrowser(url);
              }
            }}
          >
            <img
              src={require("../../assets/images/chat-widget.png")}
              alt="logo"
              className="login-mobile-qr"
              style={{
                width: "100%",
                height: "100%",
              }}
            />
            {this.state.notificationCount > 0 && (
              <div className="header-chat-widget-badge">
                {this.state.notificationCount > 99
                  ? "99+"
                  : this.state.notificationCount}
              </div>
            )}
          </div>
        )}
        <div
          className="header-search-container"
          style={this.props.isCollapsed ? { width: "369px" } : {}}
        >
          <SearchBox />
        </div>
        <div
          className="setting-icon-parrent"
          style={this.props.isCollapsed ? { marginLeft: "430px" } : {}}
        >
          <div
            className="setting-icon-container"
            onClick={() => {
              this.props.handleSortDisplay(!this.props.isSortDisplay);
            }}
            onMouseLeave={() => {
              this.props.handleSortDisplay(false);
            }}
            style={{ top: "18px" }}
          >
            <span
              data-tooltip-id="my-tooltip"
              data-tooltip-content={this.props.t("Sort by")}
              data-tooltip-place="left"
            >
              <span className="icon-sort-desc header-sort-icon"></span>
            </span>
          </div>
          <div
            className="setting-icon-container"
            onClick={() => {
              this.props.handleSetting(true);
              this.props.handleAbout(false);
            }}
            onMouseLeave={() => {
              this.props.handleAbout(false);
            }}
            style={{ marginTop: "2px" }}
          >
            <span
              data-tooltip-id="my-tooltip"
              data-tooltip-content={this.props.t("Setting")}
              data-tooltip-place="left"
            >
              <span
                className="icon-setting setting-icon"
                style={{ fontSize: "25px" }}
              ></span>
            </span>
          </div>
        </div>

        {/* 本地全功能模式：已移除 Pro 入口与试用/续费提示 */}
        {KookitConfig.CloudMode !== "production" ? (
          <div className="header-report-container" style={{ right: "300px" }}>
            <span
              style={{
                color: "red",
                opacity: 1,
                fontWeight: "bold",
              }}
            >
              <Trans>TEST</Trans>
              <span> </span>
            </span>
          </div>
        ) : null}

        <ImportLocal
          {...({
            handleDrag: this.props.handleDrag,
          } as any)}
        />
        <UpdateInfo />
      </div>
    );
  }
}

export default Header;
