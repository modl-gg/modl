import { THEME } from "../studio/theme";
import { escapeHtml, REVEAL_SCRIPT, sceneHtml, windowFrame } from "./document";

export interface FolderEntry {
  name: string;
  detail: string;
}

export interface FileExcerpt {
  title: string;
  lines: readonly string[];
}

const FOLDER_STYLE = `
.folder{display:flex;flex-direction:column;gap:2px;min-height:120px}
.entry{display:flex;justify-content:space-between;padding:12px 14px;border-radius:10px;font-size:17px}
.entry.on{background:hsl(240 3.7% 15%)}
.entry .detail{color:${THEME.muted};font-variant-numeric:tabular-nums}
`;

const FILE_STYLE = `
.files{display:flex;flex-direction:column;gap:20px;width:1120px}
.files .window{width:100%}
.code{margin:0;font:16px/1.75 ${THEME.mono};white-space:pre-wrap;word-break:break-all;color:${THEME.foreground}}
.code .line{display:block}
`;

export function folderScene(title: string, entries: readonly FolderEntry[]): string {
  const rows = entries
    .map((entry) => `<div class="entry reveal"><span>${escapeHtml(entry.name)}</span><span class="detail">${escapeHtml(entry.detail)}</span></div>`)
    .join("");
  return sceneHtml({ style: FOLDER_STYLE, body: windowFrame(title, `<div class="folder">${rows}</div>`), script: REVEAL_SCRIPT });
}

export function filesScene(files: readonly FileExcerpt[]): string {
  const windows = files
    .map((file) => {
      const lines = file.lines.map((line) => `<span class="line">${line === "" ? " " : escapeHtml(line)}</span>`).join("");
      return `<div class="reveal">${windowFrame(file.title, `<pre class="code">${lines}</pre>`)}</div>`;
    })
    .join("");
  return sceneHtml({ style: FILE_STYLE, body: `<div class="files">${windows}</div>`, script: REVEAL_SCRIPT });
}

export function megabytes(bytes: number): string {
  return `${(bytes / 1_000_000).toFixed(1)} MB`;
}
