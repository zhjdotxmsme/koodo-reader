import React from "react";
import "./modeControl.css";
import { ModeControlProps, ModeControlState } from "./interface";
import { ConfigService } from "../../../assets/lib/kookit-extra-browser.min";
import { shouldAutoDoublePage } from "../../../utils/reader/readerModeUtil";
import { Trans } from "react-i18next";

class ModeControl extends React.Component<ModeControlProps, ModeControlState> {
  // A window drag fires `resize` for every pixel, and each call would
  // re-render the whole book. Throttle to one check per frame and remember
  // that we already switched, so the drag costs one re-render, not hundreds.
  private resizeFrame: number | null = null;
  private autoDoubleApplied = false;

  constructor(props: ModeControlProps) {
    super(props);
    this.state = {};
  }

  componentDidMount() {
    // A6: auto double-page in portrait when the user enables the option
    window.addEventListener("resize", this.handleResize);
    this.handleResize();
  }

  componentWillUnmount() {
    window.removeEventListener("resize", this.handleResize);
    if (this.resizeFrame !== null) {
      window.cancelAnimationFrame(this.resizeFrame);
      this.resizeFrame = null;
    }
  }

  handleResize = () => {
    if (this.resizeFrame !== null) {
      return;
    }
    this.resizeFrame = window.requestAnimationFrame(() => {
      this.resizeFrame = null;
      this.checkAutoDoublePage();
    });
  };

  checkAutoDoublePage = () => {
    try {
      const enabled =
        ConfigService.getReaderConfig("isPortraitAutoDouble") === "yes";
      const mode = this.props.readerMode || "single";
      if (
        shouldAutoDoublePage({
          enabled,
          width: window.innerWidth,
          height: window.innerHeight,
          mode,
        })
      ) {
        // `this.props.readerMode` is Redux state and updates asynchronously,
        // so several resize events in the same tick would all see the OLD
        // mode and each trigger a full book re-render. The flag makes the
        // switch happen once.
        if (this.autoDoubleApplied) {
          return;
        }
        this.autoDoubleApplied = true;
        this.handleChangeMode("double");
      } else {
        // Leaving portrait (or switching the option off) re-arms the check.
        this.autoDoubleApplied = false;
      }
    } catch {
      // non-fatal: orientation preference simply stays unchanged
    }
  };

  handleChangeMode = (mode: string) => {
    if (
      (this.props.currentBook.format === "PDF" &&
        !ConfigService.getAllListConfig("convertPDFBooks").includes(
          this.props.currentBook.key
        )) ||
      this.props.currentBook.format.startsWith("CB")
    ) {
      ConfigService.setReaderConfig("pdfReaderMode", mode);
    } else {
      ConfigService.setReaderConfig("readerMode", mode);
    }

    this.props.handleReaderMode(mode);
    this.props.renderBookFunc();
  };
  render() {
    return (
      <div className="background-color-setting">
        <div
          className="background-color-text"
          style={{ position: "relative", bottom: "15px" }}
        >
          <Trans>View mode</Trans>
        </div>
        <div className="single-control-container">
          <div
            className="single-mode-container"
            onClick={() => {
              this.handleChangeMode("single");
            }}
            style={this.props.readerMode === "single" ? {} : { opacity: 0.4 }}
          >
            <span className="icon-single-page single-page-icon"></span>
          </div>

          <div
            className="double-mode-container"
            onClick={() => {
              this.handleChangeMode("double");
            }}
            style={this.props.readerMode === "double" ? {} : { opacity: 0.4 }}
          >
            <span className="icon-two-page two-page-icon"></span>
          </div>

          <div
            className="double-mode-container"
            onClick={() => {
              this.handleChangeMode("scroll");
            }}
            style={this.props.readerMode === "scroll" ? {} : { opacity: 0.4 }}
          >
            <span className="icon-scroll two-page-icon"></span>
          </div>
        </div>
      </div>
    );
  }
}
export default ModeControl;
