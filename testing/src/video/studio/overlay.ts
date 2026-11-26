import { LABEL_BOX_STYLE, THEME } from "./theme";

export const OVERLAY_GLOBAL = "__modlVideo";

const OVERLAY_STYLE = `
#modl-video-cursor{position:fixed;left:0;top:0;width:22px;height:22px;z-index:2147483647;pointer-events:none;opacity:0;
  transform:translate(var(--x,640px),var(--y,420px));transition:transform var(--d,600ms) cubic-bezier(.45,0,.2,1),opacity .2s ease}
#modl-video-cursor.on{opacity:1}
.modl-video-ripple{position:fixed;width:30px;height:30px;margin:-15px 0 0 -15px;border-radius:50%;border:2px solid ${THEME.accent};
  z-index:2147483646;pointer-events:none;animation:modl-video-ripple .45s ease-out forwards}
@keyframes modl-video-ripple{from{transform:scale(.3);opacity:1}to{transform:scale(1.15);opacity:0}}
#modl-video-label{position:fixed;left:50%;top:14px;z-index:2147483645;pointer-events:none;opacity:0;
  transform:translate(-50%,-6px);transition:opacity .25s ease,transform .25s ease;
  ${LABEL_BOX_STYLE}}
#modl-video-label.on{opacity:1;transform:translate(-50%,0)}
`;

const CURSOR_SVG = `<svg viewBox="0 0 24 24" width="22" height="22"><path d="M4 2l16 9.5-7 1.5-3.5 7z" fill="#fff" stroke="#0d0d10" stroke-width="1.4" stroke-linejoin="round"/></svg>`;

export const OVERLAY_SCRIPT = `(() => {
  const style = ${JSON.stringify(OVERLAY_STYLE)};
  const cursorSvg = ${JSON.stringify(CURSOR_SVG)};
  let parts = null;
  const install = () => {
    if (parts && parts.host.isConnected) return parts;
    const host = document.createElement("modl-video-overlay");
    const root = host.attachShadow({ mode: "closed" });
    const sheet = document.createElement("style");
    sheet.textContent = style;
    const cursor = document.createElement("div");
    cursor.id = "modl-video-cursor";
    cursor.innerHTML = cursorSvg;
    const label = document.createElement("div");
    label.id = "modl-video-label";
    root.append(sheet, cursor, label);
    document.documentElement.appendChild(host);
    parts = { host, root, cursor, label };
    return parts;
  };
  let labelTimer = 0;
  const position = (x, y, ms) => {
    const { cursor } = install();
    cursor.style.setProperty("--d", ms + "ms");
    cursor.style.setProperty("--x", x + "px");
    cursor.style.setProperty("--y", y + "px");
    cursor.classList.add("on");
  };
  window.${OVERLAY_GLOBAL} = {
    place(x, y) {
      position(x, y, 0);
    },
    move(x, y, ms) {
      position(x, y, ms);
    },
    hideCursor() {
      install().cursor.classList.remove("on");
    },
    ripple(x, y) {
      const { root } = install();
      const ripple = document.createElement("div");
      ripple.className = "modl-video-ripple";
      ripple.style.left = x + "px";
      ripple.style.top = y + "px";
      root.appendChild(ripple);
      setTimeout(() => ripple.remove(), 600);
    },
    label(text) {
      const { label } = install();
      clearTimeout(labelTimer);
      if (!text) {
        label.classList.remove("on");
        return;
      }
      const show = () => {
        label.textContent = text;
        label.classList.add("on");
      };
      if (label.classList.contains("on") && label.textContent !== text) {
        label.classList.remove("on");
        labelTimer = setTimeout(show, 260);
      } else {
        show();
      }
    },
  };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", install);
  else install();
})();`;

export type OverlayMethod = "place" | "move" | "hideCursor" | "ripple" | "label";

export function overlayCall(method: OverlayMethod, ...args: readonly (number | string | null)[]): string {
  return `window.${OVERLAY_GLOBAL}.${method}(${args.map((arg) => JSON.stringify(arg)).join(",")})`;
}
