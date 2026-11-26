export interface ParsedArgs {
  positional: string[];
  flags: Record<string, string | true>;
}

export function parseArgs(argv: readonly string[], booleanFlags: ReadonlySet<string>): ParsedArgs {
  const positional: string[] = [];
  const flags: Record<string, string | true> = {};
  const remaining = argv.values();
  for (const arg of remaining) {
    if (!arg.startsWith("--")) {
      positional.push(arg);
      continue;
    }
    const key = arg.slice(2);
    const value = booleanFlags.has(key) ? undefined : remaining.next().value;
    flags[key] = value ?? true;
  }
  return { positional, flags };
}

export function flagSet(flags: ParsedArgs["flags"], key: string): boolean {
  return flags[key] === true;
}

export function flagText(flags: ParsedArgs["flags"], key: string): string | undefined {
  const value = flags[key];
  return typeof value === "string" ? value : undefined;
}

export function flagNumber(flags: ParsedArgs["flags"], key: string): number | undefined {
  const value = flagText(flags, key);
  return value === undefined ? undefined : Number(value);
}

export function printOut(data: unknown): void {
  process.stdout.write(`${typeof data === "string" ? data : JSON.stringify(data, null, 2)}\n`);
}
