import type { ConsoleLine } from "../scenes/console";
import type { FolderEntry } from "../scenes/files";
import type { Camera, Footage, Pane, Shot, ShotContent, TeaserEdit, Transition } from "../teaser/edit";
import type { BeatClock } from "../teaser/score";

export interface MontageShot {
  readonly footage: Footage;
  readonly caption: string;
  readonly beats: number;
  readonly pushIn: boolean;
  readonly speed: number;
}

export interface BanMoment {
  readonly world: Footage;
  readonly command: string;
  readonly output: readonly ConsoleLine[];
  readonly chat: readonly string[];
  readonly kick: string;
  readonly panel: Footage;
  readonly panelArrivalAt: number;
}

export interface TeaserMaterial {
  readonly proxy: string;
  readonly jars: readonly FolderEntry[];
  readonly boot: readonly ConsoleLine[];
  readonly marked: readonly string[];
  readonly wizard: Footage;
  readonly ban: BanMoment;
  readonly montage: readonly MontageShot[];
}

export const TEASER_SECONDS = 60;
const MONTAGE_BEATS = 32;
const LISTS = [
  { heading: "Platforms", items: ["Paper", "Folia", "Spigot", "Velocity", "BungeeCord"], beats: 5 },
  { heading: "Databases", items: ["SQLite", "MySQL", "MariaDB", "PostgreSQL"], beats: 5 },
  { heading: "Imports", items: ["LiteBans", "AdvancedBan", "LibertyBans", "BanManager", "BanSystem"], beats: 6 },
] as const;
const END_CARD = ["modl", "v3", "github.com/modl-gg/modl"] as const;
const PUSH_IN = 1.32;

const CUT: Transition = { kind: "cut", seconds: 0 };
const fade = (seconds: number): Transition => ({ kind: "fade", seconds });
const slide = (seconds: number): Transition => ({ kind: "slide", seconds });
const zoom = (seconds: number): Transition => ({ kind: "zoom", seconds });

function drift(direction: number, depth = 1): Camera {
  return {
    from: { rotateX: 4 * depth, rotateY: -7 * direction * depth, scale: 0.95 },
    to: { rotateX: 1.5 * depth, rotateY: -2.5 * direction * depth, scale: 1 },
  };
}

class Cutter {
  private readonly shots: Shot[] = [];

  add(start: number, end: number, enter: Transition, content: ShotContent): void {
    if (end <= start) throw new Error(`shot ${String(this.shots.length)} ends before it starts (${start.toFixed(2)} to ${end.toFixed(2)} s)`);
    this.shots.push({ id: `s${String(this.shots.length)}`, start, end, enter, content });
  }

  edit(seconds: number): TeaserEdit {
    return { shots: [...this.shots], seconds };
  }
}

function gamePane(ban: BanMoment, chatSpan: { from: number; to: number }, disconnectAt: number): Pane {
  return { kind: "game", world: ban.world, chat: ban.chat, chatSpan, disconnect: ban.kick, disconnectAt };
}

export function teaserEdit(clock: BeatClock, material: TeaserMaterial): TeaserEdit {
  const cutter = new Cutter();
  const o = (beat: number): number => clock.opening(beat);
  const d = (beat: number): number => clock.drop(beat);
  const local = (beat: number, start: number): number => o(beat) - start;

  cutter.add(0, o(12), CUT, { kind: "brand", lines: ["modl"], at: [o(2)] });

  cutter.add(o(12), o(16), fade(0.8), {
    kind: "single",
    pane: { kind: "folder", title: `${material.proxy}/plugins/`, entries: material.jars, arriving: material.jars.length - 1, arriveAt: local(14, o(12)) },
    camera: drift(1, 0.6),
    caption: null,
  });

  cutter.add(o(16), o(22), slide(0.45), {
    kind: "single",
    pane: { kind: "console", title: `${material.proxy} console`, lines: material.boot, reveal: { from: 0.1, to: local(20, o(16)) }, marked: material.marked, markAt: local(20, o(16)) + 0.15, typed: null },
    camera: drift(-1, 0.6),
    caption: null,
  });

  const wizardSeconds = material.wizard.frames.reduce((sum, frame) => sum + frame.seconds, 0);
  cutter.add(o(22), o(26), zoom(0.45), {
    kind: "single",
    pane: { kind: "screen", footage: material.wizard, speed: wizardSeconds / (o(26) - o(22)), inPoint: 0, zoom: 1.12 },
    camera: drift(1),
    caption: "Setup",
  });

  const ban = material.ban;
  const banStart = o(26);
  cutter.add(banStart, o(31), slide(0.45), {
    kind: "split",
    first: {
      kind: "console",
      title: `${material.proxy} console`,
      lines: ban.output,
      reveal: { from: local(28, banStart) + 0.2, to: local(28, banStart) + 0.5 },
      marked: [],
      markAt: null,
      typed: { text: ban.command, span: { from: 0.4, to: local(28, banStart) } },
    },
    second: gamePane(ban, { from: 0, to: 0.4 }, local(29, banStart) - local(27, banStart)),
    secondAt: local(27, banStart),
    camera: drift(-1, 0.7),
  });

  const arrivalStart = o(31);
  const panelAt = 0.4;
  cutter.add(arrivalStart, clock.dropAt, fade(0.5), {
    kind: "split",
    first: gamePane(ban, { from: 0, to: 0.1 }, 0),
    second: { kind: "screen", footage: ban.panel, speed: 1, inPoint: Math.max(0, ban.panelArrivalAt - 1.6), zoom: PUSH_IN },
    secondAt: panelAt,
    camera: drift(1, 0.7),
  });

  let beat = 0;
  material.montage.forEach((shot, index) => {
    const enter = index === 0 ? zoom(0.36) : beat % 4 === 0 ? slide(0.24) : CUT;
    cutter.add(d(beat), d(beat + shot.beats), enter, {
      kind: "single",
      pane: { kind: "screen", footage: shot.footage, speed: shot.speed, inPoint: 0, zoom: shot.pushIn ? PUSH_IN : 1 },
      camera: drift(index % 2 === 0 ? 1 : -1),
      caption: shot.caption,
    });
    beat += shot.beats;
  });
  if (beat !== MONTAGE_BEATS) throw new Error(`the montage runs ${String(beat)} beats, not ${String(MONTAGE_BEATS)}`);

  for (const list of LISTS) {
    const start = d(beat);
    cutter.add(start, d(beat + list.beats), slide(0.3), {
      kind: "list",
      heading: list.heading,
      items: list.items,
      at: list.items.map((_, index) => d(beat + index) - start),
    });
    beat += list.beats;
  }

  const endStart = d(beat);
  cutter.add(endStart, TEASER_SECONDS, fade(0.6), { kind: "brand", lines: END_CARD, at: [0.1, d(beat + 2) - endStart, d(beat + 4) - endStart] });
  return cutter.edit(TEASER_SECONDS);
}
