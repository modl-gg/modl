import { mkdirSync } from "node:fs";
import { basename, join } from "node:path";
import { harnessLabels } from "../docker";
import { PANEL_JAVA_VERSION, temurinJre } from "../java-runtime";
import { ContainerLog, type LogMark } from "../logs";
import { RELEASE_MIRROR_DIR } from "../paths";
import { ENVELOPE_FILE, releaseFixture, type ReleaseName } from "../release-fixtures";
import { hostUser } from "../settings";
import { replaceSharedFiles } from "../shared-files";
import { removePath } from "../util";

export const RELEASE_MIRROR_SERVICE = "releases";

const PORT = 8000;
const MOUNT = "/releases";
const REQUEST = /"(?:GET|HEAD) \//;

export const RELEASE_MIRROR_URL = `http://${RELEASE_MIRROR_SERVICE}:${PORT}`;

function mirrorContainer(project: string): string {
  return `${project}-${RELEASE_MIRROR_SERVICE}`;
}

export function releaseMirrorEnvironment(updateFixture: boolean): Record<string, string> {
  return updateFixture ? { MODL_UPDATE_BASE_URL: RELEASE_MIRROR_URL } : {};
}

export function releaseMirrorService(project: string): Record<string, unknown> {
  return {
    image: temurinJre(PANEL_JAVA_VERSION),
    container_name: mirrorContainer(project),
    labels: harnessLabels(),
    user: hostUser(),
    command: ["java", "-m", "jdk.httpserver", "-b", "0.0.0.0", "-p", String(PORT), "-d", MOUNT, "-o", "info"],
    volumes: [`${RELEASE_MIRROR_DIR}:${MOUNT}:ro`],
    read_only: true,
    cap_drop: ["ALL"],
    security_opt: ["no-new-privileges:true"],
    restart: "no",
  };
}

export class ReleaseMirror {
  private readonly log: ContainerLog;

  constructor(project: string) {
    this.log = new ContainerLog(RELEASE_MIRROR_SERVICE, mirrorContainer(project));
  }

  clear(): void {
    removePath(join(RELEASE_MIRROR_DIR, "latest"));
    removePath(join(RELEASE_MIRROR_DIR, "download"));
  }

  async publishFiles(name: ReleaseName): Promise<void> {
    const fixture = releaseFixture(name);
    const versioned = join(RELEASE_MIRROR_DIR, "download", `v${fixture.version}`);
    mkdirSync(versioned, { recursive: true });
    const files = [fixture.server, fixture.embedded, fixture.plugin, fixture.envelope];
    await replaceSharedFiles(versioned, files.map((file) => [file, basename(file)] as const));
  }

  async publish(name: ReleaseName): Promise<void> {
    await this.publishFiles(name);
    const fixture = releaseFixture(name);
    const latest = join(RELEASE_MIRROR_DIR, "latest", "download");
    mkdirSync(latest, { recursive: true });
    await replaceSharedFiles(latest, [[fixture.envelope, ENVELOPE_FILE]]);
  }

  async mark(): Promise<LogMark> {
    return this.log.mark();
  }

  async requests(since: LogMark): Promise<string[]> {
    return (await this.log.find(REQUEST, since)).map((line) => line.text);
  }
}
