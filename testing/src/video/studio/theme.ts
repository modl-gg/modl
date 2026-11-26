export const VIDEO_SIZE = { width: 1920, height: 1080 } as const;
export const CSS_VIEWPORT = { width: 1280, height: 720 } as const;
export const DEVICE_SCALE = VIDEO_SIZE.width / CSS_VIEWPORT.width;
export const FRAME_RATE = 30;
export const CROSSFADE_SECONDS = 0.4;

export const THEME = {
  background: "#0D0D0F",
  card: "hsl(240 10% 11%)",
  border: "hsl(240 3.7% 18%)",
  foreground: "hsl(0 0% 98%)",
  muted: "hsl(240 5% 64.9%)",
  accent: "hsl(207 90% 54%)",
  sans: `ui-sans-serif, system-ui, sans-serif, "Apple Color Emoji", "Segoe UI Emoji"`,
  mono: `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace`,
} as const;

export const LABEL_BOX_STYLE = `font:600 17px/1.2 ${THEME.sans};color:${THEME.foreground};background:${THEME.card};border:1px solid ${THEME.border};border-radius:10px;padding:10px 18px;white-space:nowrap;letter-spacing:.005em`;
