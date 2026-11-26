import { SETUP_STEPS } from "../lib/contract";
import { EXAMPLE_PLAYER } from "../seed/dataset";

export type ScreenAccess = "owner" | "anonymous";

export interface ScreenContext {
  reportTicketId: string | null;
  publicTicketPath: string | null;
}

export interface Screen {
  id: string;
  access: ScreenAccess;
  path(context: ScreenContext): string | null;
}

export type ColorScheme = "dark" | "light";
export type Device = "desktop" | "mobile";

export interface Variant {
  id: string;
  scheme: ColorScheme;
  device: Device;
}

export const VARIANTS: readonly Variant[] = (["dark", "light"] as const).flatMap((scheme) =>
  (["desktop", "mobile"] as const).map((device) => ({ id: `${scheme}-${device}`, scheme, device })),
);

export const SETTINGS_PAGES = [
  "profile", "security",
  "panel", "minecraft-servers", "email", "webhooks",
  "members", "roles", "sign-in",
  "punishment-types", "escalation", "player-addresses", "replays",
  "forms", "labels", "quick-replies", "ai-moderation",
  "homepage", "knowledgebase", "theme", "custom-css",
  "storage", "database", "data-retention", "updates", "status",
] as const;

function fixed(id: string, access: ScreenAccess, path: string): Screen {
  return { id, access, path: () => path };
}

export const PANEL_SCREENS: readonly Screen[] = [
  fixed("panel-home", "owner", "/panel"),
  fixed("panel-lookup", "owner", "/panel/lookup"),
  fixed("player-window", "owner", `/panel?player=${EXAMPLE_PLAYER.uuid}`),
  fixed("player-page", "owner", `/panel/player/${EXAMPLE_PLAYER.uuid}`),
  fixed("tickets", "owner", "/panel/tickets"),
  { id: "ticket-report", access: "owner", path: (context) => (context.reportTicketId === null ? null : `/panel/tickets/${encodeURIComponent(context.reportTicketId)}`) },
  fixed("audit", "owner", "/panel/audit"),
  ...SETTINGS_PAGES.map((page) => fixed(`settings-${page}`, "owner", `/panel/settings/${page}`)),
  fixed("public-home", "anonymous", "/"),
  fixed("public-knowledgebase", "anonymous", "/knowledgebase"),
  fixed("public-article", "anonymous", "/article/server-rules"),
  fixed("public-appeal", "anonymous", "/appeal"),
  fixed("public-submit-ticket", "anonymous", "/submit-ticket"),
  { id: "public-ticket", access: "anonymous", path: (context) => context.publicTicketPath },
  fixed("sign-in", "anonymous", "/panel/auth"),
];

export const SETUP_CODE_SCREEN = "setup-code";

export function setupScreenId(step: string): string {
  return `setup-${step}`;
}

export const SETUP_SCREEN_IDS: readonly string[] = [SETUP_CODE_SCREEN, ...SETUP_STEPS.map(setupScreenId)];

export function shotFile(screen: string, variant: Variant): string {
  return `${screen}.${variant.id}.png`;
}

export const SCREENS_WITH_A_PERMANENT_SPINNER: ReadonlySet<string> = new Set(["setup-servers", "settings-minecraft-servers"]);
export const SCREENS_THAT_POLL: ReadonlySet<string> = new Set(["panel-home", "setup-account"]);

export interface StableText {
  pattern: RegExp;
  replacement: string;
}

export const STABLE_TEXT: ReadonlyMap<string, readonly StableText[]> = new Map([
  ["settings-status", [
    { pattern: /^\d+[dhms](?: \d+[dhms])*$/, replacement: "2m" },
    { pattern: /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z/g, replacement: "2026-06-15T13:58:00Z" },
  ]],
  ["settings-storage", [{ pattern: /\d+(?:\.\d+)? (?:GB|TB)/g, replacement: "500 GB" }]],
]);
