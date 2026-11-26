import { LABEL_BOX_STYLE } from "../studio/theme";
import { escapeHtml } from "./document";

const STYLE = `
html,body{margin:0;background:transparent}
.caption{display:inline-block;${LABEL_BOX_STYLE};-webkit-font-smoothing:antialiased}
`;

export function captionScene(text: string): string {
  return `<!doctype html><html><head><meta charset="utf-8"><style>${STYLE}</style></head><body><div class="caption">${escapeHtml(text)}</div></body></html>`;
}
