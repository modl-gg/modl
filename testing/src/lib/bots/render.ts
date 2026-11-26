import prismarineChat from "prismarine-chat";
import { z } from "zod";
import { plainText } from "../util";

type ChatMessageConstructor = ReturnType<typeof prismarineChat>;

const nbtTag = z.object({ type: z.unknown(), value: z.unknown() });
const nbtCompound = z.record(z.string(), z.unknown());
const nbtList = z.object({ type: z.unknown(), value: z.array(z.unknown()) });

function isNbtTag(value: unknown): boolean {
  return typeof value === "object" && value !== null && "type" in value && "value" in value;
}

function simplifyValue(value: unknown, type: unknown): unknown {
  if (type === "compound") {
    return Object.fromEntries(Object.entries(nbtCompound.parse(value)).map(([key, child]) => [key, simplifyTag(child)]));
  }
  if (type === "list") {
    const list = nbtList.parse(value);
    return list.value.map((element) => simplifyValue(element, list.type));
  }
  return value;
}

function simplifyTag(raw: unknown): unknown {
  const tag = nbtTag.parse(raw);
  return simplifyValue(tag.value, tag.type);
}

function simplifyNbt(raw: unknown): unknown {
  try {
    return simplifyTag(raw);
  } catch {
    return raw;
  }
}

function parseJsonText(raw: string): unknown {
  const trimmed = raw.trim();
  if (!trimmed.startsWith("{") && !trimmed.startsWith("[") && !trimmed.startsWith('"')) return null;
  try {
    const parsed: unknown = JSON.parse(trimmed);
    return parsed;
  } catch {
    return null;
  }
}

export class ComponentRenderer {
  private readonly ChatMessage: ChatMessageConstructor;

  constructor(version: string) {
    this.ChatMessage = prismarineChat(version);
  }

  render(raw: unknown): string {
    if (raw === null || raw === undefined) return "";
    if (typeof raw === "string") {
      const parsed = parseJsonText(raw);
      return parsed === null ? plainText(raw) : this.renderComponent(parsed);
    }
    return this.renderComponent(isNbtTag(raw) ? simplifyNbt(raw) : raw);
  }

  private renderComponent(component: unknown): string {
    const json = JSON.stringify(component);
    try {
      return plainText(this.ChatMessage.fromNotch(json).toString());
    } catch {
      return plainText(typeof component === "string" ? component : json);
    }
  }

  renderStyled(raw: unknown): string {
    if (raw === null || raw === undefined) return "";
    const parsed = typeof raw === "string" ? parseJsonText(raw) : null;
    if (typeof raw === "string" && parsed === null) return legacyCodes(raw);
    const component = typeof raw === "string" ? parsed : isNbtTag(raw) ? simplifyNbt(raw) : raw;
    try {
      return legacyCodes(this.ChatMessage.fromNotch(JSON.stringify(component)).toMotd());
    } catch {
      return legacyCodes(JSON.stringify(component));
    }
  }

  renderStyledLore(lore: string | string[] | null | undefined): string[] {
    if (lore === null || lore === undefined) return [];
    return (Array.isArray(lore) ? lore : [lore]).map((line) => this.renderStyled(line));
  }

  renderLore(lore: string | string[] | null | undefined): string[] {
    if (lore === null || lore === undefined) return [];
    return (Array.isArray(lore) ? lore : [lore]).map((line) => this.render(line)).filter((line) => line.length > 0);
  }
}

function legacyCodes(text: string): string {
  return text.replace(/\u00a7/g, "&");
}
