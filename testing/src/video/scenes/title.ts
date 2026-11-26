import { readFileSync } from "node:fs";
import { join } from "node:path";
import { REPO_ROOT } from "../../lib/paths";
import { escapeHtml, sceneHtml } from "./document";

const WORDMARK = join(REPO_ROOT, "panel", "client", "src", "assets", "modl-favicon.svg");

const STYLE = `
.title{display:flex;flex-direction:column;align-items:center;gap:28px}
.title img{height:46px}
.title h1{margin:0;font-size:34px;font-weight:600;letter-spacing:-.01em}
`;

export function titleScene(title: string): string {
  const wordmark = `data:image/svg+xml;base64,${readFileSync(WORDMARK).toString("base64")}`;
  return sceneHtml({ style: STYLE, body: `<div class="title"><img alt="" src="${wordmark}"><h1>${escapeHtml(title)}</h1></div>` });
}
