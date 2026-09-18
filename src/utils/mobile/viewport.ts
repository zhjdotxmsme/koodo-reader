/**
 * Mobile-mode viewport gate.
 *
 * Design: .openteams/specs/2026-09-18-mobile-mode-design.html
 * A single body-level class (`mobile-mode`) drives all mobile styling in
 * src/assets/styles/mobile.css — no media queries. Below the breakpoint the
 * class is present, at/above it is absent, so desktop rendering is
 * structurally unaffected.
 */

/** Viewport widths below this value render the mobile layout. */
export const MOBILE_BREAKPOINT = 700;

/** True when the given CSS viewport width should render the mobile layout. */
export function isMobileViewport(width: number): boolean {
  return width < MOBILE_BREAKPOINT;
}

/**
 * Attach the `mobile-mode` class to <body> and keep it in sync with window
 * resizes (rAF-throttled). Returns a detach function.
 */
export function initMobileMode(): () => void {
  const apply = () => {
    document.body.classList.toggle(
      "mobile-mode",
      isMobileViewport(window.innerWidth)
    );
  };
  apply();
  let rafId = 0;
  const onResize = () => {
    if (rafId) {
      cancelAnimationFrame(rafId);
    }
    rafId = requestAnimationFrame(() => {
      rafId = 0;
      apply();
    });
  };
  window.addEventListener("resize", onResize);
  return () => {
    window.removeEventListener("resize", onResize);
    if (rafId) {
      cancelAnimationFrame(rafId);
      rafId = 0;
    }
  };
}
