import { readFileSync } from "node:fs";
import { join } from "node:path";
import { REPO_ROOT } from "../../lib/paths";
import { THEME } from "../studio/theme";
import type { TeaserEdit } from "./edit";
import type { TeaserLayout } from "./layout";
import { shotHtml } from "./shots";

const BRAND_FONT = join(REPO_ROOT, "node_modules", "@fontsource", "audiowide", "files", "audiowide-latin-400-normal.woff2");
const ACCENT_RGB = "32 148 243";

function brandFontFace(): string {
  let font: Buffer;
  try {
    font = readFileSync(BRAND_FONT);
  } catch {
    throw new Error(`the brand font ${BRAND_FONT} is missing; run npm ci in the repository`);
  }
  return `@font-face{font-family:"Audiowide";src:url(data:font/woff2;base64,${font.toString("base64")}) format("woff2");font-display:block}`;
}

function style(layout: TeaserLayout): string {
  const vertical = layout.vertical;
  return `
${brandFontFace()}
*{box-sizing:border-box}
html,body{margin:0;width:${String(layout.profile.width)}px;height:${String(layout.profile.height)}px;overflow:hidden;background:${THEME.background};color:${THEME.foreground};font-family:${THEME.sans};-webkit-font-smoothing:antialiased}
.shot{position:absolute;inset:0;overflow:hidden;background:${THEME.background};will-change:transform,opacity,filter}
.wash{position:absolute;inset:-12%;opacity:.85;pointer-events:none;will-change:transform;
  background:radial-gradient(ellipse 55% 45% at 50% 46%,rgb(${ACCENT_RGB} / .30),transparent 70%),radial-gradient(ellipse 70% 40% at 18% 108%,rgb(${ACCENT_RGB} / .16),transparent 70%),radial-gradient(ellipse 60% 35% at 86% -6%,rgb(${ACCENT_RGB} / .12),transparent 70%)}
.grain{position:absolute;inset:0;pointer-events:none;opacity:.5;background:radial-gradient(ellipse 90% 80% at 50% 50%,transparent 55%,rgb(0 0 0 / .55))}
.shot-brand .wash{opacity:0}
.stage3d{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:${vertical ? "40px" : "18px"};perspective:2400px}
.rig{transform-style:preserve-3d;display:flex;align-items:center;justify-content:center}
.box{position:relative;overflow:hidden;border-radius:${vertical ? "28px" : "20px"};border:1px solid rgb(${ACCENT_RGB} / .38);background:hsl(240 8% 14%);
  box-shadow:0 50px 140px rgb(0 0 0 / .65),0 18px 40px rgb(0 0 0 / .45),0 0 0 1px rgb(255 255 255 / .06) inset,0 0 120px rgb(${ACCENT_RGB} / .22)}
.screen{background:${THEME.background}}
.footage{position:absolute;left:0;top:0;transform-origin:0 0;display:block}
.window{display:flex;flex-direction:column}
.window>header{flex:none;padding:${vertical ? "20px 30px" : "16px 26px"};border-bottom:1px solid hsl(240 5% 22%);background:hsl(240 8% 16%);color:hsl(240 5% 78%);font-size:${vertical ? "26px" : "20px"};font-weight:600}
.window>.body{position:relative;flex:1;overflow:hidden}
.console{position:absolute;inset:0;padding:${vertical ? "26px 28px" : "22px 26px"};font:${vertical ? "25px/1.55" : "25px/1.6"} ${THEME.mono};overflow:hidden}
.console .scroll{position:absolute;left:${vertical ? "28px" : "26px"};right:${vertical ? "28px" : "26px"};top:${vertical ? "26px" : "22px"}}
.console .line{white-space:pre-wrap;word-break:break-word;display:none;border-radius:6px;margin:0 -8px;padding-right:8px;transition:none}
.console .line.input{display:block;color:${THEME.foreground}}
.console .prompt{color:${THEME.accent}}
.console .caret{color:${THEME.muted}}
.console .meta{color:${THEME.muted}}
.console mark{background:none;color:inherit;border:1px solid transparent;border-radius:4px;padding:0 3px;margin:0 -4px}
.console .line.marked{background:rgb(${ACCENT_RGB} / .14);box-shadow:inset 3px 0 0 ${THEME.accent}}
.console .line.marked mark{color:${THEME.accent};border-color:${THEME.accent}}
.folder{padding:${vertical ? "22px" : "18px"};display:flex;flex-direction:column;gap:4px}
.entry{display:flex;justify-content:space-between;gap:24px;padding:${vertical ? "22px 24px" : "18px 20px"};border-radius:12px;font-size:${vertical ? "32px" : "30px"}}
.entry.on{background:rgb(${ACCENT_RGB} / .14);box-shadow:inset 0 0 0 1px rgb(${ACCENT_RGB} / .5)}
.entry .detail{color:${THEME.muted};font-variant-numeric:tabular-nums}
.game{background:#000}
.world-shot{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;transform-origin:50% 50%}
.chat{position:absolute;left:20px;right:20px;bottom:28px;display:flex;flex-direction:column;align-items:flex-start;gap:3px}
.chat .msg{display:none;padding:5px 14px;background:rgb(0 0 0 / .5);font-size:${vertical ? "30px" : "28px"};line-height:1.45;color:#fff;text-shadow:2px 2px 0 rgb(0 0 0 / .45)}
.disconnect{position:absolute;inset:0;opacity:0;display:flex;align-items:center;justify-content:center;padding:48px;background:rgb(12 10 8 / .72);backdrop-filter:blur(8px) saturate(.7)}
.disconnect .reason{max-width:90%;text-align:center;white-space:pre-wrap;font-size:${vertical ? "36px" : "34px"};line-height:1.5;color:#fff;text-shadow:3px 3px 0 rgb(0 0 0 / .5)}
.split{display:flex;align-items:center;justify-content:center}
.split.column{flex-direction:column}
.slot{will-change:transform,opacity}
.caption{text-align:center;font-size:${vertical ? "44px" : "30px"};font-weight:650;letter-spacing:.01em;color:${THEME.foreground};opacity:0;text-shadow:0 2px 18px rgb(0 0 0 / .6)}
.brand{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:${vertical ? "34px" : "26px"}}
.brand-line{opacity:0;white-space:nowrap}
.brand-0{font-family:"Audiowide",cursive;font-size:${vertical ? "230px" : "250px"};line-height:1;color:${THEME.accent}}
.brand-1{font-family:"Audiowide",cursive;font-size:${vertical ? "86px" : "72px"};line-height:1;color:${THEME.foreground}}
.brand-2{font:${vertical ? "500 34px" : "500 30px"} ${THEME.mono};color:${THEME.muted}}
.list{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:${vertical ? "56px" : "44px"}}
.list-heading{font-size:${vertical ? "34px" : "26px"};font-weight:600;letter-spacing:.22em;text-transform:uppercase;color:${THEME.muted};opacity:0}
.list-items{display:flex;${vertical ? "flex-direction:column;align-items:center;gap:26px" : "flex-wrap:wrap;justify-content:center;max-width:1840px;column-gap:44px;row-gap:22px"}}
.list-item{font-size:${vertical ? "92px" : "60px"};font-weight:700;letter-spacing:-.02em;line-height:1.05;color:${THEME.foreground};opacity:0;white-space:nowrap}
.list-item.accent{color:${THEME.accent}}
`;
}

const APPLY_SCRIPT = `window.teaser = {
  reveal(element, count) {
    const children = [...element.children];
    children.forEach((child, index) => { child.style.display = index < count ? "block" : "none"; });
    const scroll = element.closest(".scroll");
    const frame = scroll ? scroll.parentElement : null;
    if (!scroll || !frame) return;
    const last = children[Math.min(count, children.length) - 1];
    const bottom = last ? last.offsetTop + last.offsetHeight + element.offsetTop : element.offsetTop;
    const room = frame.clientHeight - 2 * scroll.offsetTop;
    scroll.style.transform = "translateY(" + Math.min(0, room - bottom) + "px)";
  },
  async apply(states) {
    const pending = [];
    for (const state of states) {
      const element = document.getElementById(state.id);
      if (!element) throw new Error("no element " + state.id);
      if (state.className !== undefined) element.className = state.className;
      if (state.style) for (const [key, value] of Object.entries(state.style)) element.style.setProperty(key, value);
      if (state.text !== undefined) element.textContent = state.text;
      if (state.reveal !== undefined) this.reveal(element, state.reveal);
      if (state.src !== undefined && element.getAttribute("src") !== state.src) {
        element.setAttribute("src", state.src);
        pending.push(element.decode());
      }
    }
    await Promise.all(pending);
  },
};`;

export function teaserPage(edit: TeaserEdit, layout: TeaserLayout): string {
  return [
    "<!doctype html><html><head><meta charset=\"utf-8\">",
    `<style>${style(layout)}</style>`,
    "</head><body>",
    edit.shots.map((shot) => shotHtml(shot, layout)).join(""),
    `<script>${APPLY_SCRIPT}</script>`,
    "</body></html>",
  ].join("");
}
