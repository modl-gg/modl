import { escapeHtml } from "../scenes/document";
import type { BrandShot, Camera, ListShot, Pane, Shot, SingleShot, SplitShot, TransitionKind } from "./edit";
import { SPLIT_GAP, type Box, type TeaserLayout } from "./layout";
import { clamp, easeInOut, easeOut, fixed, lerp, posePoint, poseTransform, px, window01 } from "./motion";
import { paneHtml, paneStates, type FrameSource, type NodeState } from "./panes";

const PARALLAX_PX = 40;
const SPLIT_SECONDS = 0.5;
const CAPTION_DELAY = 0.12;
const CAPTION_SECONDS = 0.3;
const LINE_SECONDS = 0.7;
const ITEM_SECONDS = 0.32;
const ITEM_ACCENT_SECONDS = 0.45;

export interface ShotMoment {
  readonly time: number;
  readonly local: number;
  readonly progress: number;
  readonly entering: number;
  readonly leaving: number;
  readonly exit: TransitionKind;
  readonly layer: number;
}

function slot(shot: Shot, name: string): string {
  return `${shot.id}-${name}`;
}

function paneBox(layout: TeaserLayout, pane: Pane, split: boolean): Box {
  return split ? layout.splitBox() : layout.single(pane);
}

function contentHtml(shot: Shot, layout: TeaserLayout): string {
  const content = shot.content;
  switch (content.kind) {
    case "single":
      return `<div class="stage3d"><div class="rig" id="${slot(shot, "rig")}">${paneHtml(content.pane, slot(shot, "a"), paneBox(layout, content.pane, false))}</div>${
        content.caption === null ? "" : `<div class="caption" id="${slot(shot, "cap")}">${escapeHtml(content.caption)}</div>`
      }</div>`;
    case "split": {
      const box = layout.splitBox();
      return `<div class="stage3d"><div class="rig" id="${slot(shot, "rig")}"><div class="split ${layout.splitDirection()}" style="gap:${px(SPLIT_GAP)}"><div class="slot" id="${slot(shot, "sa")}">${paneHtml(content.first, slot(shot, "a"), box)}</div><div class="slot" id="${slot(shot, "sb")}">${paneHtml(content.second, slot(shot, "b"), box)}</div></div></div></div>`;
    }
    case "brand":
      return `<div class="brand">${content.lines.map((line, index) => `<div class="brand-line brand-${String(index)}" id="${slot(shot, `l${String(index)}`)}">${escapeHtml(line)}</div>`).join("")}</div>`;
    case "list":
      return `<div class="list"><div class="list-heading" id="${slot(shot, "h")}">${escapeHtml(content.heading)}</div><div class="list-items">${content.items
        .map((item, index) => `<div class="list-item" id="${slot(shot, `i${String(index)}`)}">${escapeHtml(item)}</div>`)
        .join("")}</div></div>`;
  }
}

export function shotHtml(shot: Shot, layout: TeaserLayout): string {
  return `<section class="shot shot-${shot.content.kind}" id="${shot.id}" style="visibility:hidden"><div class="wash" id="${slot(shot, "wash")}"></div>${contentHtml(shot, layout)}<div class="grain"></div></section>`;
}

function axis(layout: TeaserLayout): "X" | "Y" {
  return layout.vertical ? "Y" : "X";
}

function transitionStyle(moment: ShotMoment, enter: TransitionKind, layout: TeaserLayout): Record<string, string> {
  const incoming = moment.entering;
  const outgoing = moment.leaving;
  let opacity = 1;
  let scale = 1;
  let shift = 0;
  let blur = 0;
  switch (enter) {
    case "cut":
      opacity = incoming >= 1 ? 1 : 0;
      break;
    case "fade":
      opacity = easeInOut(incoming);
      break;
    case "zoom":
      opacity = easeOut(incoming);
      scale = lerp(0.86, 1, easeOut(incoming));
      blur = (1 - incoming) * 6;
      break;
    case "slide":
      shift = (1 - easeOut(incoming)) * 100;
      blur = Math.sin(Math.PI * incoming) * 7;
      break;
  }
  switch (moment.exit) {
    case "cut":
    case "fade":
      break;
    case "zoom":
      scale *= lerp(1, 1.22, easeInOut(outgoing));
      opacity *= 1 - easeInOut(outgoing);
      blur += outgoing * 8;
      break;
    case "slide":
      shift -= easeInOut(outgoing) * 32;
      blur += Math.sin(Math.PI * outgoing) * 7;
      break;
  }
  return {
    visibility: "visible",
    opacity: fixed(opacity),
    transform: `translate${axis(layout)}(${fixed(shift, 2)}%) scale(${fixed(scale, 4)})`,
    filter: blur > 0.05 ? `blur(${px(blur)})` : "none",
    "z-index": String(moment.layer),
  };
}

function captionState(shot: Shot, moment: ShotMoment): NodeState {
  const shown = easeOut(window01(moment.local, CAPTION_DELAY, CAPTION_SECONDS));
  return { id: slot(shot, "cap"), style: { opacity: fixed(shown), transform: `translateY(${px((1 - shown) * 10)})` } };
}

function parallax(shot: Shot, camera: Camera, moment: ShotMoment): NodeState {
  const direction = Math.sign(camera.from.rotateY) || 1;
  const travel = lerp(1, -1, easeInOut(moment.progress));
  return { id: slot(shot, "wash"), style: { transform: `translate3d(${px(travel * PARALLAX_PX * direction)}, ${px(travel * PARALLAX_PX * 0.4)}, 0)` } };
}

function singleStates(shot: Shot, content: SingleShot, moment: ShotMoment, layout: TeaserLayout, frames: FrameSource): NodeState[] {
  const pose = posePoint(content.camera.from, content.camera.to, moment.progress);
  return [
    parallax(shot, content.camera, moment),
    { id: slot(shot, "rig"), style: { transform: poseTransform(pose) } },
    ...paneStates(content.pane, slot(shot, "a"), paneBox(layout, content.pane, false), layout, moment, frames),
    ...(content.caption === null ? [] : [captionState(shot, moment)]),
  ];
}

function splitStates(shot: Shot, content: SplitShot, moment: ShotMoment, layout: TeaserLayout, frames: FrameSource): NodeState[] {
  const box = layout.splitBox();
  const pose = posePoint(content.camera.from, content.camera.to, moment.progress);
  const joined = easeInOut(window01(moment.local, content.secondAt, SPLIT_SECONDS));
  const half = ((layout.vertical ? box.height : box.width) + SPLIT_GAP) / 2;
  const direction = axis(layout);
  return [
    parallax(shot, content.camera, moment),
    { id: slot(shot, "rig"), style: { transform: poseTransform(pose) } },
    { id: slot(shot, "sa"), style: { transform: `translate${direction}(${px(lerp(half, 0, joined))})` } },
    { id: slot(shot, "sb"), style: { opacity: fixed(joined), transform: `translate${direction}(${px(lerp(half * 0.6, 0, joined))})` } },
    ...paneStates(content.first, slot(shot, "a"), box, layout, moment, frames),
    ...paneStates(content.second, slot(shot, "b"), box, layout, { ...moment, local: moment.local - content.secondAt }, frames),
  ];
}

function brandStates(shot: Shot, content: BrandShot, moment: ShotMoment): NodeState[] {
  const wash = clamp(0.35 + 0.65 * easeInOut(moment.local / 3.2));
  const breathing = 0.92 + 0.08 * Math.sin(moment.local * 1.7);
  const lines = content.lines.map((_, index): NodeState => {
    const shown = window01(moment.local, content.at[index] ?? 0, index === 0 ? LINE_SECONDS * 2 : LINE_SECONDS);
    const eased = easeOut(shown);
    const glow = index === 0 ? `drop-shadow(0 0 ${px(30 * eased * breathing)} hsl(207 90% 54% / ${fixed(0.55 * eased)}))` : "";
    const blur = (1 - eased) * (index === 0 ? 18 : 8);
    return {
      id: slot(shot, `l${String(index)}`),
      style: {
        opacity: fixed(eased),
        filter: `${blur > 0.05 ? `blur(${px(blur)}) ` : ""}${glow}`.trim() || "none",
        transform: `translateY(${px((1 - eased) * (index === 0 ? 0 : 16))}) scale(${fixed(lerp(index === 0 ? 0.94 : 1, 1, eased), 4)})`,
        "letter-spacing": index === 0 ? `${fixed(lerp(0.18, 0.02, eased), 3)}em` : "normal",
      },
    };
  });
  return [{ id: slot(shot, "wash"), style: { opacity: fixed(wash * breathing) } }, ...lines];
}

function listStates(shot: Shot, content: ListShot, moment: ShotMoment): NodeState[] {
  const heading = easeOut(window01(moment.local, 0, ITEM_SECONDS));
  return [
    { id: slot(shot, "h"), style: { opacity: fixed(heading), transform: `translateY(${px((1 - heading) * 12)})` } },
    ...content.items.map((_, index): NodeState => {
      const at = content.at[index] ?? 0;
      const shown = easeOut(window01(moment.local, at, ITEM_SECONDS));
      const accent = moment.local >= at && moment.local < at + ITEM_ACCENT_SECONDS;
      return {
        id: slot(shot, `i${String(index)}`),
        className: accent ? "list-item accent" : "list-item",
        style: { opacity: fixed(shown), transform: `translateY(${px((1 - shown) * 28)}) scale(${fixed(lerp(0.92, 1, shown), 4)})` },
      };
    }),
  ];
}

export function shotStates(shot: Shot, moment: ShotMoment, layout: TeaserLayout, frames: FrameSource): NodeState[] {
  const root: NodeState = { id: shot.id, style: transitionStyle(moment, shot.enter.kind, layout) };
  const content = shot.content;
  switch (content.kind) {
    case "single":
      return [root, ...singleStates(shot, content, moment, layout, frames)];
    case "split":
      return [root, ...splitStates(shot, content, moment, layout, frames)];
    case "brand":
      return [root, ...brandStates(shot, content, moment)];
    case "list":
      return [root, ...listStates(shot, content, moment)];
  }
}

export function hiddenShot(shot: Shot): NodeState {
  return { id: shot.id, style: { visibility: "hidden" } };
}
