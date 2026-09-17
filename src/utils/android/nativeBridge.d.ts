// Type surface for nativeBridge.js (protocol constants + helpers).
// Protocol mirrors: android/app/src/main/java/com/koodoreader/reader/NativeEventDispatcher.kt

export const EVENTS: {
  BOOK_INITED: string;
  CACHE: string;
  CHAPTER_PAGES: string;
  CONTENT_LOADED: string;
  ERROR: string;
  FINISH_DOWNLOAD: string;
  GET_OCR_RESULT: string;
  LEFT: string;
  LINK_CLICKED: string;
  METADATA: string;
  PAGE_CHANGED: string;
  PINCH_ZOOM: string;
  RIGHT: string;
  SCROLL_BOTTOM: string;
  SCROLL_TEXT: string;
  SCROLL_TOP: string;
  SELECT_TEXT: string;
  SELECT_TEXT_AFTER_TOUCH: string;
  SELECTION_CHANGE: string;
  SWIPE: string;
  USER_AGENT: string;
  VIEW_IMAGE: string;
};
export const KNOWN_EVENTS: Set<string>;
export const PAGE_TURN_HOOKS: Record<string, "nextPage" | "prevPage">;
export const SELECT_TEXT_EVENTS: Set<string>;
export const MENU_ACTIONS: string[];
export const MENU_LABEL_KEYS: Record<string, string>;

export function isNativeMobile(win?: any): boolean;
export function getIsMobile(win?: any): "yes" | "no";
export function pageTurnHook(eventName: string): "nextPage" | "prevPage" | null;
export function isSelectTextEvent(eventName: string): boolean;
export function validateSelectTextPayload(
  payload: any
): { ok: boolean; reason?: string; text?: string; position?: any };
export function isExternalHref(href: string): boolean;
export function buildMenuLabels(t: (key: string) => string): Record<string, string>;
export function createHostApi(api: {
  prevPage?: () => any;
  nextPage?: () => any;
  openSelectionMenu?: () => any;
}): {
  prevPage: () => any;
  nextPage: () => any;
  openSelectionMenu: () => any;
};
