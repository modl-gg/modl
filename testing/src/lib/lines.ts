import { ARTIFACTS, type VelocityArtifactId } from "./artifacts";
import { temurinJre } from "./java-runtime";
import { PAPER_BUILDS, type PaperBuild } from "./paper";
import { TOPOLOGY_NAMES, type LineName, type TopologyName, type UpOptions } from "./state";

export interface PlatformLine {
  name: LineName;
  description: string;
  paper: PaperBuild;
  velocity: VelocityArtifactId | null;
  unavailable: Partial<Record<TopologyName, string>>;
}

export const LINES: Record<LineName, PlatformLine> = {
  latest: {
    name: "latest",
    description: "newest Minecraft that Mineflayer can join, newest Velocity",
    paper: PAPER_BUILDS.paperLatest,
    velocity: "velocityLatest",
    unavailable: {},
  },
  "1.21": {
    name: "1.21",
    description: "previous line: Minecraft 1.21, Velocity 3, Java 21 (the minimum for the built-in panel and the modl server)",
    paper: PAPER_BUILDS.paper121,
    velocity: "velocity121",
    unavailable: {},
  },
  legacy: {
    name: "legacy",
    description: "Minecraft 1.8.8 backends on Java 8; the panel runs on a Java 21 proxy or the separately hosted modl server",
    paper: PAPER_BUILDS.paperLegacy,
    velocity: null,
    unavailable: {
      standalone:
        "Paper 1.8.8 runs on Java 8, which cannot host the built-in panel; the embedded-needs-java-21 scenario checks that refusal on the remote topology",
      network:
        "Paper 1.8.8 has no Velocity modern forwarding, and 1.8 networks run behind BungeeCord, which the network-bungee topology covers",
    },
  },
  folia: {
    name: "folia",
    description: "Folia (regionized Paper) of the newest Minecraft that Mineflayer can join, newest Velocity",
    paper: PAPER_BUILDS.foliaLatest,
    velocity: "velocityLatest",
    unavailable: {},
  },
};

export function lineOf(options: Pick<UpOptions, "line">): PlatformLine {
  return LINES[options.line];
}

export function lineTopologies(line: PlatformLine): TopologyName[] {
  return TOPOLOGY_NAMES.filter((topology) => line.unavailable[topology] === undefined);
}

export function requireVelocity(line: PlatformLine): VelocityArtifactId {
  if (line.velocity === null) throw new Error(`the ${line.name} line has no Velocity build`);
  return line.velocity;
}

export function requestedJava(options: Pick<UpOptions, "line" | "java">): number {
  return options.java ?? lineOf(options).paper.java;
}

export function javaImage(options: Pick<UpOptions, "line" | "java">, minimumJava: number): string {
  return temurinJre(Math.max(requestedJava(options), minimumJava));
}

export interface LineSummary {
  line: LineName;
  description: string;
  minecraft: string;
  server: string;
  paper: string;
  velocity: string | null;
  bungeecord: string;
  java: number;
  topologies: TopologyName[];
  unavailable: Partial<Record<TopologyName, string>>;
}

export function lineSummary(line: PlatformLine): LineSummary {
  return {
    line: line.name,
    description: line.description,
    minecraft: line.paper.minecraftVersion,
    server: line.paper.software,
    paper: line.paper.version,
    velocity: line.velocity === null ? null : ARTIFACTS[line.velocity].version,
    bungeecord: ARTIFACTS.bungeecord.version,
    java: line.paper.java,
    topologies: lineTopologies(line),
    unavailable: line.unavailable,
  };
}
