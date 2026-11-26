import { SparseBlockMap } from '../format/WorldLayout.ts';
import { BLOCK_ENTITY_STRIDE } from './PreparedReplay.ts';

export function blockEntitySites(sites: Int32Array): SparseBlockMap<number> {
  const map = new SparseBlockMap<number>();
  for (let offset = 0; offset + BLOCK_ENTITY_STRIDE <= sites.length; offset += BLOCK_ENTITY_STRIDE) {
    map.set(sites[offset] ?? 0, sites[offset + 1] ?? 0, sites[offset + 2] ?? 0, sites[offset + 3] ?? 0);
  }
  return map;
}
