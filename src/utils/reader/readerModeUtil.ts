/**
 * Portrait auto double-page decision (pure).
 *
 * When the user enables "portrait auto double" and the window is in
 * portrait orientation (height > width), the reader should present a
 * double-page spread instead of a single page — except when the current
 * mode is already "double" or the user explicitly chose "scroll".
 */

export interface PortraitDoubleInput {
  enabled: boolean;
  width: number;
  height: number;
  /** Current reader mode: "single" | "double" | "scroll" */
  mode: string;
}

export function shouldAutoDoublePage(input: PortraitDoubleInput): boolean {
  if (!input || !input.enabled) {
    return false;
  }
  const { width, height, mode } = input;
  if (
    typeof width !== "number" ||
    typeof height !== "number" ||
    !Number.isFinite(width) ||
    !Number.isFinite(height) ||
    width <= 0 ||
    height <= 0
  ) {
    return false;
  }
  if (height <= width) {
    return false;
  }
  if (mode === "double" || mode === "scroll") {
    return false;
  }
  return true;
}
