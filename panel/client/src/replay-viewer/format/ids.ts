const NAMESPACE = 'minecraft:';

export function bareName(id: string): string {
  return id.startsWith(NAMESPACE) ? id.slice(NAMESPACE.length) : id;
}
