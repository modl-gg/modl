import type { ConsoleLine } from "../scenes/console";
import type { FolderEntry } from "../scenes/files";
import type { TextBox } from "../studio/cut-text";
import type { TimedFrame } from "../studio/segment";
import type { Viewport } from "../studio/stage";

export interface Region {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

export interface Footage {
  readonly name: string;
  readonly frames: readonly TimedFrame[];
  readonly viewport: Viewport;
  readonly focus: Region;
  readonly text: readonly TextBox[];
  readonly wholeFrame: boolean;
}

export interface Pose {
  readonly rotateX: number;
  readonly rotateY: number;
  readonly scale: number;
}

export interface Camera {
  readonly from: Pose;
  readonly to: Pose;
}

export interface Span {
  readonly from: number;
  readonly to: number;
}

export interface ScreenPane {
  readonly kind: "screen";
  readonly footage: Footage;
  readonly speed: number;
  readonly inPoint: number;
  readonly zoom: number;
}

export interface TypedCommand {
  readonly text: string;
  readonly span: Span;
}

export interface ConsolePane {
  readonly kind: "console";
  readonly title: string;
  readonly lines: readonly ConsoleLine[];
  readonly reveal: Span;
  readonly marked: readonly string[];
  readonly markAt: number | null;
  readonly typed: TypedCommand | null;
}

export interface FolderPane {
  readonly kind: "folder";
  readonly title: string;
  readonly entries: readonly FolderEntry[];
  readonly arriving: number;
  readonly arriveAt: number;
}

export interface GamePane {
  readonly kind: "game";
  readonly world: Footage;
  readonly chat: readonly string[];
  readonly chatSpan: Span;
  readonly disconnect: string | null;
  readonly disconnectAt: number;
}

export type Pane = ScreenPane | ConsolePane | FolderPane | GamePane;

export interface SingleShot {
  readonly kind: "single";
  readonly pane: Pane;
  readonly camera: Camera;
  readonly caption: string | null;
}

export interface SplitShot {
  readonly kind: "split";
  readonly first: Pane;
  readonly second: Pane;
  readonly secondAt: number;
  readonly camera: Camera;
}

export interface BrandShot {
  readonly kind: "brand";
  readonly lines: readonly string[];
  readonly at: readonly number[];
}

export interface ListShot {
  readonly kind: "list";
  readonly heading: string;
  readonly items: readonly string[];
  readonly at: readonly number[];
}

export type ShotContent = SingleShot | SplitShot | BrandShot | ListShot;

export type TransitionKind = "cut" | "fade" | "zoom" | "slide";

export interface Transition {
  readonly kind: TransitionKind;
  readonly seconds: number;
}

export interface Shot {
  readonly id: string;
  readonly start: number;
  readonly end: number;
  readonly enter: Transition;
  readonly content: ShotContent;
}

export interface TeaserEdit {
  readonly shots: readonly Shot[];
  readonly seconds: number;
}

export interface ShotClock {
  readonly local: number;
  readonly length: number;
  readonly progress: number;
}
