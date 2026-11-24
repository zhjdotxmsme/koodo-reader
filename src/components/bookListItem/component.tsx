import React from "react";
import "./bookListItem.css";
import { BookItemProps } from "./interface";
import { Trans } from "react-i18next";
import { withRouter } from "react-router-dom";
import EmptyCover from "../emptyCover";
import ActionDialog from "../dialogs/actionDialog";
import toast from "react-hot-toast";
import { ConfigService } from "../../assets/lib/kookit-extra-browser.min";
import { useBookItem } from "../bookItem/useBookItem";
import { getFileNameWithoutExtension } from "../../utils/common";
import {
  clampMenuPosition,
  estimateMenuHeight,
  CONTEXT_MENU_WIDTH,
} from "../../utils/common";
import { buildBookMetaLines } from "../../utils/manager/bookShelf";

declare var window: any;

const BookListItem: React.FC<BookItemProps> = (props) => {
  const {
    left,
    setLeft,
    top,
    setTop,
    direction,
    setDirection,
    isHover,
    setIsHover,
    cover,
    isCoverExist,
    isBookOffline,
    handleJump,
    handleSelectIconClick,
    handleBookDragStart,
    getPercentage,
    isFavoriteBook,
    isTopBook,
    setIsFavorite,
  } = useBookItem(props);

  const handleMoreAction = (event: any) => {
    event.preventDefault();
    const e = event || window.event;
    let x = e.clientX;
    if (x > document.body.clientWidth - 300) {
      x = x - 180;
    }
    const pos = clampMenuPosition(
      x,
      document.body.clientHeight - e.clientY > 250
        ? e.clientY
        : e.clientY - 200,
      CONTEXT_MENU_WIDTH,
      estimateMenuHeight(9)
    );
    setLeft(pos.left);
    setTop(pos.top);
    props.handleActionDialog(true);
    props.handleReadingBook(props.book);
  };

  const percentage = getPercentage();
  const actionProps = { left, top };

  // Compact (极简列表) layout — C1/C2: minimal row with title, author and
  // an intro line when the book carries a description.
  if (props.compact === true) {
    const title =
      ConfigService.getReaderConfig("isUseOriginalName") === "yes"
        ? getFileNameWithoutExtension(props.book.path, props.book.name)
        : props.book.name;
    const metaLines = buildBookMetaLines(
      {
        name: title,
        author: props.book.author,
        description: props.book.description,
      },
      { showIntro: true, showLastRead: true, introLimit: 40, lastReadLabel: props.t("Last read") }
    );
    return (
      <>
        <div
          className={`book-compact-item${
            props.isSelected ? " selected" : ""
          }`}
          draggable
          onDragStart={handleBookDragStart}
          onClick={(event) => {
            if (props.isSelectBook) {
              handleSelectIconClick(event);
            } else {
              handleJump(event);
            }
          }}
          onContextMenu={(event: any) => {
            handleMoreAction(event);
          }}
        >
          {isCoverExist ? (
            <img className="book-compact-cover" src={cover} alt="" />
          ) : (
            <div className="book-compact-cover book-compact-cover-empty">
              {title.slice(0, 1)}
            </div>
          )}
          <div className="book-compact-main">
            <div className="book-compact-title">{title}</div>
            {props.book.author ? (
              <div className="book-compact-author">{props.book.author}</div>
            ) : null}
            {metaLines.map((line, i) => (
              <div key={i} className="book-compact-meta">
                {line}
              </div>
            ))}
          </div>
        </div>
        {/* The compact row still handles right-click, so the context menu has
            to be rendered here too — otherwise the click opens the dialog
            state but nothing appears on screen. */}
        {props.isOpenActionDialog && props.book.key === props.currentBook.key ? (
          <div className="action-dialog-parent">
            <ActionDialog {...(actionProps as any)} />
          </div>
        ) : null}
      </>
    );
  }

  return (
    <>
      <div
        className="book-list-item-container"
        draggable
        onDragStart={handleBookDragStart}
        onContextMenu={(event) => {
          handleMoreAction(event);
        }}
      >
        {!isCoverExist ||
        (props.book.format === "PDF" &&
          ConfigService.getReaderConfig("isDisablePDFCover") === "yes") ? (
          <div
            className="book-item-list-cover"
            onClick={() => {
              handleJump();
            }}
            style={{ height: "65px" }}
            onMouseEnter={() => {
              setIsHover(true);
            }}
            onMouseLeave={() => {
              setIsHover(false);
            }}
          >
            <div className="book-item-image" style={{ height: "65px" }}>
              <EmptyCover
                {...{
                  format: props.book.format,
                  title:
                    ConfigService.getReaderConfig("isUseOriginalName") === "yes"
                      ? getFileNameWithoutExtension(
                          props.book.path,
                          props.book.name
                        )
                      : props.book.name,
                  scale: 0.43,
                }}
              />
            </div>
          </div>
        ) : (
          <div
            className="book-item-list-cover"
            onClick={(event) => {
              handleJump(event);
            }}
            onMouseEnter={() => {
              setIsHover(true);
            }}
            onMouseLeave={() => {
              setIsHover(false);
            }}
          >
            <img
              src={cover}
              alt=""
              draggable={false}
              className="book-item-image"
              style={{ width: "100%" }}
              onLoad={(res: any) => {
                if (
                  res.target.naturalHeight / res.target.naturalWidth >
                  74 / 47
                ) {
                  setDirection("horizontal");
                } else {
                  setDirection("vertical");
                }
              }}
            />
          </div>
        )}
        {props.isSelectBook || isHover ? (
          <span
            className="icon-message book-selected-icon"
            onMouseEnter={() => {
              setIsHover(true);
            }}
            onClick={(event) => {
              handleSelectIconClick(event);
            }}
            style={
              props.isSelected
                ? { left: "18px", bottom: "5px", opacity: 1 }
                : { left: "18px", bottom: "5px", color: "#eee" }
            }
          ></span>
        ) : null}
        <p
          className="book-item-list-title"
          onClick={(event) => {
            handleJump(event);
          }}
        >
          <div className="book-item-list-subtitle">
            <div className="book-item-list-subtitle-text">
              {!isBookOffline && (
                <span className="icon-cloud book-download-action"></span>
              )}
              {(isFavoriteBook() || isTopBook()) && (
                <span className="book-item-list-status">
                  {isFavoriteBook() && (
                    <span className="icon-heart book-item-list-favorite"></span>
                  )}
                  {isTopBook() && (
                    <span className="icon-pin book-item-list-top"></span>
                  )}
                </span>
              )}
              {ConfigService.getReaderConfig("isUseOriginalName") === "yes"
                ? getFileNameWithoutExtension(props.book.path, props.book.name)
                : props.book.name}
            </div>
          </div>

          <p className="book-item-list-percentage">
            {percentage && !isNaN(parseFloat(percentage))
              ? percentage === "0"
                ? "New"
                : percentage === "1"
                  ? "Done"
                  : (parseFloat(percentage) * 100).toFixed(2)
              : "0"}
            {percentage &&
              !isNaN(parseFloat(percentage)) &&
              percentage !== "0" &&
              percentage !== "1" && <span>%</span>}
          </p>
          <div className="book-item-list-author">
            <Trans>{props.book.author || "Unknown author"}</Trans>
          </div>
        </p>
      </div>
      {props.isOpenActionDialog && props.book.key === props.currentBook.key ? (
        <div className="action-dialog-parent">
          <ActionDialog {...(actionProps as any)} />
        </div>
      ) : null}
    </>
  );
};

export default withRouter(BookListItem as any);
