import test from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { basename, extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { z } from 'zod';

interface LocaleTree { [key: string]: string | LocaleTree }
type LocaleStrings = Map<string, string>;

const localeTreeSchema: z.ZodType<LocaleTree> = z.lazy(() => z.record(z.string(), z.union([z.string(), localeTreeSchema])));

const LOCALES_DIRECTORY = fileURLToPath(new URL('.', import.meta.url));
const SOURCE_DIRECTORY = fileURLToPath(new URL('..', import.meta.url));
const REFERENCE_LOCALE = 'en';
const SOURCE_EXTENSIONS = new Set(['.ts', '.tsx']);
const PLURAL_SUFFIX = /_(?:zero|one|two|few|many|other)$/;
const PLACEHOLDER = /\{\{\s*([^}\s,]+)[^}]*\}\}/g;
const EM_DASH = '\u2014';
const PLURAL_SAMPLES = Array.from({ length: 201 }, (_, count) => count);
const LITERAL_KEY_REFERENCES = [
  /(?<![\w$.])(?:i18n\.)?t\(\s*(['"`])([^'"`$\\]+)\1/g,
  /\bi18nKey\s*=\s*\{?\s*(['"`])([^'"`$\\]+)\1/g,
];
const KEY_PROPERTY_REFERENCE = /\b[a-z]\w*Key\s*:\s*(['"`])([^'"`$\\]+)\1/g;

function flatten(tree: LocaleTree, prefix = '', into: LocaleStrings = new Map()): LocaleStrings {
  for (const [segment, value] of Object.entries(tree)) {
    const key = prefix ? `${prefix}.${segment}` : segment;
    if (typeof value === 'string') {
      into.set(key, value);
    } else {
      flatten(value, key, into);
    }
  }
  return into;
}

function localeCodes(): string[] {
  return readdirSync(LOCALES_DIRECTORY)
    .filter((file) => extname(file) === '.json')
    .map((file) => basename(file, '.json'))
    .sort();
}

function readLocale(code: string): LocaleStrings {
  const tree = localeTreeSchema.parse(JSON.parse(readFileSync(join(LOCALES_DIRECTORY, `${code}.json`), 'utf8')));
  return flatten(tree);
}

function isTestFile(file: string): boolean {
  return /\.test\.tsx?$/.test(file);
}

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      return entry.name === 'node_modules' ? [] : sourceFiles(path);
    }
    return SOURCE_EXTENSIONS.has(extname(entry.name)) && !isTestFile(entry.name) ? [path] : [];
  });
}

function matchedKeys(source: string, pattern: RegExp): string[] {
  return Array.from(source.matchAll(pattern), (match) => match[2] ?? '');
}

function namespaceOf(key: string): string {
  return key.split('.')[0] ?? key;
}

function referencedKeys(namespaces: Set<string>): Map<string, Set<string>> {
  const references = new Map<string, Set<string>>();
  const record = (key: string, file: string) => {
    const files = references.get(key) ?? new Set<string>();
    files.add(file);
    references.set(key, files);
  };
  for (const file of sourceFiles(SOURCE_DIRECTORY)) {
    const source = readFileSync(file, 'utf8');
    for (const pattern of LITERAL_KEY_REFERENCES) {
      matchedKeys(source, pattern).forEach((key) => record(key, file));
    }
    matchedKeys(source, KEY_PROPERTY_REFERENCE)
      .filter((key) => key.includes('.') && namespaces.has(namespaceOf(key)))
      .forEach((key) => record(key, file));
  }
  return references;
}

function resolves(key: string, strings: LocaleStrings): boolean {
  return strings.has(key) || strings.has(`${key}_other`);
}

function referenceKeyFor(key: string, reference: LocaleStrings): string | undefined {
  if (reference.has(key)) {
    return key;
  }
  const pluralOther = key.replace(PLURAL_SUFFIX, '_other');
  return pluralOther !== key && reference.has(pluralOther) ? pluralOther : undefined;
}

function pluralBases(strings: LocaleStrings): Set<string> {
  return new Set(Array.from(strings.keys())
    .filter((key) => PLURAL_SUFFIX.test(key))
    .map((key) => key.replace(PLURAL_SUFFIX, '')));
}

function pluralForms(code: string): Set<string> {
  const rules = new Intl.PluralRules(code);
  return new Set(PLURAL_SAMPLES.map((count) => rules.select(count)));
}

function placeholders(text: string): string[] {
  return Array.from(new Set(Array.from(text.matchAll(PLACEHOLDER), (match) => match[1] ?? ''))).sort();
}

const reference = readLocale(REFERENCE_LOCALE);
const translations = localeCodes().filter((code) => code !== REFERENCE_LOCALE);

await test('every translation key referenced in source exists in the reference locale', () => {
  const namespaces = new Set(Array.from(reference.keys(), namespaceOf));
  const missing = Array.from(referencedKeys(namespaces))
    .filter(([key]) => !resolves(key, reference))
    .map(([key, files]) => `${key} (${Array.from(files).sort().join(', ')})`)
    .sort();
  assert.deepEqual(missing, []);
});

await test('every locale has exactly the keys of the reference locale', () => {
  assert.ok(translations.length > 0);
  for (const code of translations) {
    const strings = readLocale(code);
    const missing = Array.from(reference.keys()).filter((key) => !strings.has(key));
    const unexpected = Array.from(strings.keys()).filter((key) => referenceKeyFor(key, reference) === undefined);
    assert.deepEqual({ code, missing, unexpected }, { code, missing: [], unexpected: [] });
  }
});

await test('every locale has each plural form its language uses', () => {
  const bases = Array.from(pluralBases(reference));
  for (const code of [REFERENCE_LOCALE, ...translations]) {
    const strings = readLocale(code);
    const forms = Array.from(pluralForms(code));
    const missing = bases
      .flatMap((base) => forms.map((form) => `${base}_${form}`))
      .filter((key) => !strings.has(key));
    assert.deepEqual({ code, missing }, { code, missing: [] });
  }
});

await test('every translation keeps the placeholders of the reference locale', () => {
  for (const code of translations) {
    const mismatched = Array.from(readLocale(code))
      .filter(([key, text]) => {
        const referenceKey = referenceKeyFor(key, reference);
        return referenceKey !== undefined
          && placeholders(text).join() !== placeholders(reference.get(referenceKey) ?? '').join();
      })
      .map(([key]) => key);
    assert.deepEqual({ code, mismatched }, { code, mismatched: [] });
  }
});

await test('no locale string contains an em-dash', () => {
  for (const code of [REFERENCE_LOCALE, ...translations]) {
    const offending = Array.from(readLocale(code))
      .filter(([, text]) => text.includes(EM_DASH))
      .map(([key]) => key);
    assert.deepEqual({ code, offending }, { code, offending: [] });
  }
});
