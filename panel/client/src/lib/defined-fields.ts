export type DefinedFields<T> = { [K in keyof T]?: Exclude<T[K], undefined> };

function isDefined<V>(value: V): value is Exclude<V, undefined> {
  return value !== undefined;
}

export function optionalField<K extends string, V>(key: K, value: V | undefined): Partial<Record<K, V>> {
  const field: Partial<Record<K, V>> = {};
  if (value !== undefined) {
    field[key] = value;
  }
  return field;
}

export function definedFields<T extends object>(value: T): DefinedFields<T> {
  const fields: DefinedFields<T> = {};
  for (const key in value) {
    const field = value[key];
    if (isDefined(field)) {
      fields[key] = field;
    }
  }
  return fields;
}
