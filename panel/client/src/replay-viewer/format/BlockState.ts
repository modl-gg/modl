export type BlockProperties = Readonly<Record<string, string>>;

export interface BlockState {
  readonly name: string;
  readonly properties: BlockProperties;
}

export const AIR_NAME = 'air';

export function propertiesKey(properties: BlockProperties): string {
  return Object.keys(properties).sort().map((key) => `${key}=${properties[key] ?? ''}`).join(',');
}

export function stateKey(state: BlockState): string {
  return `${state.name}[${propertiesKey(state.properties)}]`;
}
