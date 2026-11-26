import { escapeHtml } from "../scenes/document";
import type { ConsoleLine } from "../scenes/console";
import type { ConsolePane, FolderPane, Footage, GamePane, Pane, ScreenPane } from "./edit";
import type { Box, TeaserLayout } from "./layout";
import { framingAt } from "./layout";
import { clamp, easeInOut, easeOut, easeOutBack, fixed, lerp, px, window01 } from "./motion";

export interface NodeState {
  readonly id: string;
  readonly style?: Readonly<Record<string, string>>;
  readonly src?: string;
  readonly text?: string;
  readonly reveal?: number;
  readonly className?: string;
}

export interface FrameSource {
  urlAt(footage: Footage, seconds: number): string;
}

export interface PaneMoment {
  readonly local: number;
  readonly progress: number;
}

const ARRIVE_SECONDS = 0.45;
const PUSH_IN_SHARE = 0.7;
const DISCONNECT_SECONDS = 0.3;

function consoleLineHtml(line: ConsoleLine, id: string): string {
  const highlight = line.highlight;
  const text = highlight === null ? escapeHtml(line.text) : escapeHtml(line.text).replace(escapeHtml(highlight), () => `<mark>${escapeHtml(highlight)}</mark>`);
  const meta = line.meta === "" ? "" : `<span class="meta">${escapeHtml(line.meta)}</span> `;
  const indent = line.meta === "" ? 0 : line.meta.length + 1;
  return `<div class="line" id="${id}" style="padding-left:${String(indent)}ch;text-indent:-${String(indent)}ch">${meta}${text}</div>`;
}

function windowHtml(id: string, box: Box, title: string, body: string, className: string): string {
  return `<section class="box window ${className}" id="${id}" style="width:${px(box.width)};height:${px(box.height)}"><header>${escapeHtml(title)}</header><div class="body">${body}</div></section>`;
}

export function paneHtml(pane: Pane, id: string, box: Box): string {
  switch (pane.kind) {
    case "screen":
      return `<section class="box screen" id="${id}" style="width:${px(box.width)};height:${px(box.height)}"><img class="footage" id="${id}-img" alt="" style="width:${px(pane.footage.viewport.width)};height:${px(pane.footage.viewport.height)}"></section>`;
    case "console": {
      const typed = pane.typed === null ? "" : `<div class="line input"><span class="prompt">&gt;</span> <span id="${id}-typed"></span><span class="caret" id="${id}-caret">_</span></div>`;
      const lines = pane.lines.map((line, index) => consoleLineHtml(line, `${id}-l${String(index)}`)).join("");
      return windowHtml(id, box, pane.title, `<div class="console"><div class="scroll">${typed}<div class="lines" id="${id}-lines">${lines}</div></div></div>`, "console-window");
    }
    case "folder": {
      const rows = pane.entries
        .map((entry, index) => `<div class="entry" id="${id}-e${String(index)}"><span>${escapeHtml(entry.name)}</span><span class="detail">${escapeHtml(entry.detail)}</span></div>`)
        .join("");
      return windowHtml(id, box, pane.title, `<div class="folder">${rows}</div>`, "folder-window");
    }
    case "game": {
      const chat = pane.chat.map((html) => `<div class="msg">${html}</div>`).join("");
      const disconnect = pane.disconnect === null ? "" : `<div class="disconnect" id="${id}-dc"><div class="reason">${pane.disconnect}</div></div>`;
      return `<section class="box game" id="${id}" style="width:${px(box.width)};height:${px(box.height)}"><img class="world-shot" id="${id}-world" alt=""><div class="chat" id="${id}-chat">${chat}</div>${disconnect}</section>`;
    }
  }
}

function screenStates(pane: ScreenPane, id: string, box: Box, layout: TeaserLayout, moment: PaneMoment, frames: FrameSource): NodeState[] {
  const view = framingAt(layout.screenFraming(pane.footage, box, pane.zoom), easeInOut(clamp(moment.progress / PUSH_IN_SHARE)));
  const scale = box.width / view.width;
  const source = pane.inPoint + Math.max(0, moment.local) * pane.speed;
  return [
    {
      id: `${id}-img`,
      src: frames.urlAt(pane.footage, source),
      style: { transform: `translate(${px(-view.x * scale)}, ${px(-view.y * scale)}) scale(${fixed(scale, 5)})` },
    },
  ];
}

function revealed(count: number, span: { from: number; to: number }, local: number): number {
  if (count === 0) return 0;
  return Math.round(count * easeOut(window01(local, span.from, span.to - span.from)));
}

function consoleStates(pane: ConsolePane, id: string, moment: PaneMoment): NodeState[] {
  const states: NodeState[] = [{ id: `${id}-lines`, reveal: revealed(pane.lines.length, pane.reveal, moment.local) }];
  const typed = pane.typed;
  if (typed !== null) {
    const characters = Math.round(typed.text.length * window01(moment.local, typed.span.from, typed.span.to - typed.span.from));
    const done = moment.local >= typed.span.to;
    states.push({ id: `${id}-typed`, text: typed.text.slice(0, characters) }, { id: `${id}-caret`, style: { opacity: done ? "0" : "1" } });
  }
  const markAt = pane.markAt;
  pane.lines.forEach((line, index) => {
    if (line.name === null || !pane.marked.includes(line.name)) return;
    const on = markAt !== null && moment.local >= markAt;
    states.push({ id: `${id}-l${String(index)}`, className: on ? "line marked" : "line" });
  });
  return states;
}

function folderStates(pane: FolderPane, id: string, moment: PaneMoment): NodeState[] {
  return pane.entries.map((_, index) => {
    if (index !== pane.arriving) return { id: `${id}-e${String(index)}`, style: { opacity: "1", transform: "none" } };
    const progress = window01(moment.local, pane.arriveAt, ARRIVE_SECONDS);
    const lift = lerp(-46, 0, easeOutBack(progress));
    const highlighted = moment.local >= pane.arriveAt;
    return {
      id: `${id}-e${String(index)}`,
      className: highlighted ? "entry on" : "entry",
      style: { opacity: fixed(easeOut(progress)), transform: `translateY(${px(lift)})` },
    };
  });
}

function gameStates(pane: GamePane, id: string, moment: PaneMoment, frames: FrameSource): NodeState[] {
  const states: NodeState[] = [
    { id: `${id}-world`, src: frames.urlAt(pane.world, 0), style: { transform: `scale(${fixed(lerp(1.02, 1.08, easeInOut(moment.progress)), 4)})` } },
    { id: `${id}-chat`, reveal: revealed(pane.chat.length, pane.chatSpan, moment.local) },
  ];
  if (pane.disconnect !== null) states.push({ id: `${id}-dc`, style: { opacity: fixed(window01(moment.local, pane.disconnectAt, DISCONNECT_SECONDS)) } });
  return states;
}

export function paneStates(pane: Pane, id: string, box: Box, layout: TeaserLayout, moment: PaneMoment, frames: FrameSource): NodeState[] {
  switch (pane.kind) {
    case "screen":
      return screenStates(pane, id, box, layout, moment, frames);
    case "console":
      return consoleStates(pane, id, moment);
    case "folder":
      return folderStates(pane, id, moment);
    case "game":
      return gameStates(pane, id, moment, frames);
  }
}
