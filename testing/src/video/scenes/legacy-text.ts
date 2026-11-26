import { escapeHtml } from "./document";

const COLORS: Readonly<Record<string, string>> = {
  "0": "#000000",
  "1": "#0000AA",
  "2": "#00AA00",
  "3": "#00AAAA",
  "4": "#AA0000",
  "5": "#AA00AA",
  "6": "#FFAA00",
  "7": "#AAAAAA",
  "8": "#555555",
  "9": "#5555FF",
  a: "#55FF55",
  b: "#55FFFF",
  c: "#FF5555",
  d: "#FF55FF",
  e: "#FFFF55",
  f: "#FFFFFF",
};

interface Format {
  color: string | null;
  bold: boolean;
  italic: boolean;
  underline: boolean;
  strike: boolean;
}

const PLAIN: Format = { color: null, bold: false, italic: false, underline: false, strike: false };

function styled(text: string, format: Format): string {
  if (text === "") return "";
  const rules = [
    format.color === null ? "" : `color:${format.color}`,
    format.bold ? "font-weight:700" : "",
    format.italic ? "font-style:italic" : "",
    format.underline || format.strike ? `text-decoration:${format.underline ? "underline" : ""} ${format.strike ? "line-through" : ""}`.trim() : "",
  ].filter((rule) => rule !== "");
  const html = escapeHtml(text).replace(/\n/g, "<br>");
  return rules.length === 0 ? html : `<span style="${rules.join(";")}">${html}</span>`;
}

function apply(format: Format, code: string): Format {
  const color = COLORS[code];
  if (color !== undefined) return { ...PLAIN, color };
  switch (code) {
    case "l":
      return { ...format, bold: true };
    case "o":
      return { ...format, italic: true };
    case "n":
      return { ...format, underline: true };
    case "m":
      return { ...format, strike: true };
    case "r":
      return PLAIN;
    default:
      return format;
  }
}

export function legacyTextHtml(text: string): string {
  const parts: string[] = [];
  let format = PLAIN;
  let buffer = "";
  for (let index = 0; index < text.length; index += 1) {
    const character = text.charAt(index);
    const code = text.charAt(index + 1).toLowerCase();
    if ((character === "&" || character === "§") && /[0-9a-fk-or]/.test(code)) {
      parts.push(styled(buffer, format));
      buffer = "";
      format = apply(format, code);
      index += 1;
      continue;
    }
    buffer += character;
  }
  parts.push(styled(buffer, format));
  return parts.join("");
}

export function legacyPlainText(text: string): string {
  return text.replace(/[&§][0-9a-fk-or]/gi, "");
}
