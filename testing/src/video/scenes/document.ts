import { THEME } from "../studio/theme";

const ESCAPES: Record<string, string> = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };

export function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (character) => ESCAPES[character] ?? character);
}

const BASE_STYLE = `
*{box-sizing:border-box}
html,body{margin:0;height:100%;background:${THEME.background};color:${THEME.foreground};font-family:${THEME.sans};-webkit-font-smoothing:antialiased}
body{display:flex;align-items:center;justify-content:center}
.window{width:1120px;background:${THEME.card};border:1px solid ${THEME.border};border-radius:16px;overflow:hidden}
.window>header{padding:14px 22px;border-bottom:1px solid ${THEME.border};color:${THEME.muted};font-size:14px;font-weight:500}
.window>.body{padding:22px}
.reveal{opacity:0;transform:translateY(4px);transition:opacity .3s ease,transform .3s ease}
.reveal.on{opacity:1;transform:none}
.stage{transition:transform 7s cubic-bezier(.25,0,.25,1)}
.line{transition:filter .5s ease}
.line.dim{filter:opacity(.3)}
`;

export interface SceneDocument {
  style: string;
  body: string;
  script?: string;
}

export function sceneHtml(scene: SceneDocument): string {
  return [
    "<!doctype html><html><head><meta charset=\"utf-8\">",
    `<style>${BASE_STYLE}${scene.style}</style>`,
    "</head><body>",
    `<div class="stage">${scene.body}</div>`,
    scene.script === undefined ? "" : `<script>${scene.script}</script>`,
    "</body></html>",
  ].join("");
}

export function windowFrame(title: string, content: string, className = ""): string {
  return `<section class="window ${className}"><header>${escapeHtml(title)}</header><div class="body">${content}</div></section>`;
}

export type SceneMethod = "reveal" | "mark" | "focus" | "add" | "openInput" | "input" | "closeInput" | "show" | "tip" | "closeMenus" | "chat";

export function sceneCall(method: SceneMethod, ...args: readonly (number | string)[]): string {
  return `window.scene.${method}(${args.map((arg) => JSON.stringify(arg)).join(",")})`;
}

export const REVEAL_SCRIPT = `window.scene = {
  reveal(index) {
    const items = document.querySelectorAll(".reveal");
    if (items[index]) items[index].classList.add("on");
  },
  mark() {
    document.querySelectorAll("mark").forEach((mark) => mark.classList.add("on"));
  },
  focus(...indexes) {
    const lines = [...document.querySelectorAll(".line")];
    const focused = new Set(indexes);
    lines.forEach((line, index) => line.classList.toggle("dim", focused.size > 0 && !focused.has(index)));
    const stage = document.querySelector(".stage");
    const first = lines[indexes[0]];
    if (!stage || !first) {
      if (stage) stage.style.transform = "";
      return;
    }
    const stageBox = stage.getBoundingClientRect();
    const box = first.getBoundingClientRect();
    stage.style.transformOrigin = (box.left - stageBox.left + box.width / 2) + "px " + (box.top - stageBox.top + box.height / 2) + "px";
    stage.style.transform = "scale(1.035)";
  },
};`;
