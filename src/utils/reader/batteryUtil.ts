/**
 * Battery percent via the (partially supported) Web Battery API, with a
 * hard timeout and graceful null when unsupported (Electron may not expose
 * it). Pure-ish: no dependencies, testable by mocking `navigator.getBattery`.
 */

export interface BatteryLike {
  level: number;
  charging?: boolean;
  addEventListener?: (
    type: string,
    listener: () => void
  ) => void;
  removeEventListener?: (type: string, listener: () => void) => void;
}

interface NavigatorWithBattery extends Navigator {
  getBattery?: () => Promise<BatteryLike>;
}

export function getBatteryLevel(timeoutMs = 1500): Promise<number | null> {
  return new Promise((resolve) => {
    try {
      const nav = (typeof navigator !== "undefined"
        ? navigator
        : undefined) as NavigatorWithBattery | undefined;
      if (!nav || typeof nav.getBattery !== "function") {
        resolve(null);
        return;
      }
      let settled = false;
      const finish = (value: number | null) => {
        if (settled) return;
        settled = true;
        resolve(value);
      };
      const timer = setTimeout(() => finish(null), Math.max(1, timeoutMs));
      nav
        .getBattery()
        .then((battery) => {
          const apply = (level: number) => {
            if (
              typeof level === "number" &&
              Number.isFinite(level) &&
              level >= 0 &&
              level <= 1
            ) {
              clearTimeout(timer);
              finish(Math.round(level * 100));
            }
          };
          if (battery && typeof battery.level === "number") {
            apply(battery.level);
          } else if (battery && typeof battery.addEventListener === "function") {
            const onLevel = () => apply(battery.level);
            battery.addEventListener("levelchange", onLevel);
            // Unsubscribe when the promise settles
            setTimeout(() => {
              if (typeof battery.removeEventListener === "function") {
                battery.removeEventListener("levelchange", onLevel);
              }
            }, Math.max(1, timeoutMs) + 50);
          }
          // If nothing was available, the timeout resolves null.
        })
        .catch(() => {
          clearTimeout(timer);
          finish(null);
        });
    } catch {
      resolve(null);
    }
  });
}

/** Format a percent 0-100 into "85%" or "" for null/invalid values. */
export function formatBattery(percent: number | null): string {
  if (
    typeof percent !== "number" ||
    !Number.isFinite(percent) ||
    percent < 0 ||
    percent > 100
  ) {
    return "";
  }
  return `${Math.round(percent)}%`;
}
