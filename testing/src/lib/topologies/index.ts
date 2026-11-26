import type { TopologyName } from "../state";
import { network, networkBungee } from "./network";
import { remote } from "./remote";
import { standalone } from "./standalone";
import type { TopologyDefinition } from "./types";

export const TOPOLOGIES: Record<TopologyName, TopologyDefinition> = { standalone, network, "network-bungee": networkBungee, remote };

export function publishedPorts(definition: TopologyDefinition, options: Parameters<TopologyDefinition["servers"]>[0]): number[] {
  return definition
    .servers(options)
    .flatMap((spec) => [spec.ports.game, spec.ports.rcon, spec.ports.http])
    .filter((port): port is number => port !== undefined);
}
