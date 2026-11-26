import { existsSync, mkdirSync } from "node:fs";
import { basename, join } from "node:path";
import { ARTIFACTS, ensureArtifact, jarsUnder, type FillPin, type PaperArtifactId } from "./artifacts";
import { runOneShot } from "./docker";
import { temurinJre } from "./java-runtime";
import type { Logger } from "./log";
import { ARTIFACT_DIR, BUNDLER_DIR } from "./paths";
import { hostUser } from "./settings";
import { writeText } from "./util";

export interface PaperLaunch {
  jar: string;
  jvmOptions: string[];
  serverArgs: string;
  extraVolumes: string[];
}

export type WorldProperties = Record<string, string>;

const FLAT_WORLD_LAYERS = [
  { block: "minecraft:bedrock", height: 1 },
  { block: "minecraft:dirt", height: 2 },
  { block: "minecraft:grass_block", height: 1 },
];

const PEACEFUL_FLAT_WORLD: WorldProperties = {
  "level-type": "minecraft\\:flat",
  "generator-settings": JSON.stringify({ biome: "minecraft:plains", layers: FLAT_WORLD_LAYERS }),
  difficulty: "peaceful",
  gamemode: "survival",
};

const LEGACY_PEACEFUL_FLAT_WORLD: WorldProperties = {
  "level-type": "FLAT",
  "generator-settings": "3;minecraft:bedrock,2*minecraft:dirt,minecraft:grass;1;",
  difficulty: "0",
  gamemode: "0",
};

const SOFTWARE_NAMES: Record<FillPin["source"]["project"], string> = { paper: "Paper", folia: "Folia", velocity: "Velocity" };

function baseJvmOptions(heapMb: number): string[] {
  return ["-Xms512M", `-Xmx${heapMb}M`, "-XX:+UseG1GC", "-Djava.awt.headless=true"];
}

export abstract class PaperBuild {
  readonly artifact: PaperArtifactId;

  constructor(artifact: PaperArtifactId) {
    this.artifact = artifact;
  }

  protected get pin(): FillPin {
    return ARTIFACTS[this.artifact];
  }

  get version(): string {
    return this.pin.version;
  }

  get minecraftVersion(): string {
    return this.pin.source.version;
  }

  get java(): number {
    return this.pin.java;
  }

  get software(): string {
    return SOFTWARE_NAMES[this.pin.source.project];
  }

  get regionized(): boolean {
    return this.pin.source.project === "folia";
  }

  get reloadable(): boolean {
    return !this.regionized;
  }

  protected get patchDir(): string {
    return join(BUNDLER_DIR, basename(this.pin.file, ".jar"));
  }

  async prepare(logger: Logger): Promise<void> {
    await ensureArtifact(this.artifact, logger);
    const marker = join(this.patchDir, ".patched");
    if (existsSync(marker)) return;
    mkdirSync(this.patchDir, { recursive: true });
    logger.info(`pre-patching ${this.pin.file} once (downloads the vanilla server from Mojang)`);
    await runOneShot({
      image: temurinJre(this.java),
      user: hostUser(),
      workdir: this.patchWorkdir,
      volumes: [`${ARTIFACT_DIR}:/server:ro`, `${this.patchDir}:/bundler`],
      environment: { HOME: "/tmp" },
      command: ["java", ...this.patchOptions, "-Dpaperclip.patchonly=true", "-jar", `/server/${this.pin.file}`],
      timeoutMs: 600_000,
    });
    writeText(marker, new Date().toISOString());
  }

  patchedJars(): string[] {
    return jarsUnder(this.patchDir);
  }

  abstract launch(heapMb: number): PaperLaunch;

  abstract worldProperties(): WorldProperties;

  protected abstract readonly patchWorkdir: string;

  protected abstract readonly patchOptions: readonly string[];
}

class BundlerPaper extends PaperBuild {
  protected readonly patchWorkdir = "/tmp";
  protected readonly patchOptions: readonly string[] = ["-DbundlerRepoDir=/bundler"];

  launch(heapMb: number): PaperLaunch {
    return {
      jar: join(ARTIFACT_DIR, this.pin.file),
      jvmOptions: [...baseJvmOptions(heapMb), "-Dterminal.jline=false", "-Dterminal.ansi=false", "-DbundlerRepoDir=/bundler"],
      serverArgs: "--nogui",
      extraVolumes: [`${this.patchDir}:/bundler`],
    };
  }

  worldProperties(): WorldProperties {
    return PEACEFUL_FLAT_WORLD;
  }
}

class LegacyPaperclipPaper extends PaperBuild {
  protected readonly patchWorkdir = "/bundler";
  protected readonly patchOptions: readonly string[] = [];

  launch(heapMb: number): PaperLaunch {
    return {
      jar: join(this.patchDir, "cache", `patched_${this.minecraftVersion}.jar`),
      jvmOptions: baseJvmOptions(heapMb),
      serverArgs: "--nojline",
      extraVolumes: [],
    };
  }

  worldProperties(): WorldProperties {
    return LEGACY_PEACEFUL_FLAT_WORLD;
  }
}

export const PAPER_BUILDS = {
  paperLatest: new BundlerPaper("paperLatest"),
  paper121: new BundlerPaper("paper121"),
  paperLegacy: new LegacyPaperclipPaper("paperLegacy"),
  foliaLatest: new BundlerPaper("foliaLatest"),
} as const satisfies Record<PaperArtifactId, PaperBuild>;
