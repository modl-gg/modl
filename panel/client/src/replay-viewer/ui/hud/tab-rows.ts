import type { ReplayPlayerInfo } from '../../engine/ReplayPlayer';
import { sameList } from '../gui/same-list.ts';

export interface TabRow {
  readonly uuid: string;
  readonly name: string;
  readonly entityId: number | null;
  readonly present: boolean;
  readonly score: string;
  readonly skinHash: string | null;
  readonly slim: boolean;
}

function scoreOf(player: ReplayPlayerInfo): string {
  return player.health === null ? '' : String(Math.ceil(player.health));
}

function byListOrder(left: ReplayPlayerInfo, right: ReplayPlayerInfo): number {
  if (left.isTarget !== right.isTarget) return left.isTarget ? -1 : 1;
  if (left.present !== right.present) return left.present ? -1 : 1;
  return left.name.localeCompare(right.name);
}

export function tabRows(players: readonly ReplayPlayerInfo[]): TabRow[] {
  return [...players].sort(byListOrder).map((player) => ({
    uuid: player.uuid,
    name: player.name,
    entityId: player.entityId >= 0 ? player.entityId : null,
    present: player.present,
    score: scoreOf(player),
    skinHash: player.skinHash,
    slim: player.slim,
  }));
}

function sameRow(left: TabRow, right: TabRow): boolean {
  return left.uuid === right.uuid
    && left.name === right.name
    && left.entityId === right.entityId
    && left.present === right.present
    && left.score === right.score
    && left.skinHash === right.skinHash
    && left.slim === right.slim;
}

export function sameTabRows(left: readonly TabRow[], right: readonly TabRow[]): boolean {
  return sameList(left, right, sameRow);
}
