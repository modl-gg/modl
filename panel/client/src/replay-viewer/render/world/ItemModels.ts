import { arrayField, isRecord, recordField, stringField } from '../../assets/json.ts';
import type { ResourcePack } from '../../assets/ResourcePack.ts';
import { bareName } from '../../format/ids.ts';

const MAX_DEPTH = 12;

function firstModel(node: unknown, depth: number): string | null {
  if (!isRecord(node) || depth > MAX_DEPTH) return null;
  const type = bareName(stringField(node, 'type') ?? 'model');
  if (type === 'model') return stringField(node, 'model');
  if (type === 'special') return stringField(node, 'base');
  if (type === 'composite') {
    for (const child of arrayField(node, 'models')) {
      const found = firstModel(child, depth + 1);
      if (found !== null) return found;
    }
    return null;
  }
  const fallback = firstModel(node['fallback'], depth + 1);
  if (fallback !== null) return fallback;
  for (const key of ['on_false', 'on_true']) {
    const found = firstModel(node[key], depth + 1);
    if (found !== null) return found;
  }
  for (const entry of [...arrayField(node, 'cases'), ...arrayField(node, 'entries')]) {
    if (!isRecord(entry)) continue;
    const found = firstModel(entry['model'], depth + 1);
    if (found !== null) return found;
  }
  return null;
}

export function itemModelId(pack: ResourcePack, itemKey: string): string | null {
  const name = bareName(itemKey);
  if (pack.hasItemDefinitions()) {
    const definition = pack.itemDefinition(name);
    if (isRecord(definition)) {
      const found = firstModel(recordField(definition, 'model'), 0);
      if (found !== null) return found;
    }
  }
  const legacy = `item/${name}`;
  return pack.model(legacy) === null ? null : legacy;
}
