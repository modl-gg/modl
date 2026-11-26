import { existsSync } from "node:fs";
import { join } from "node:path";
import { artifactStatuses } from "./artifacts";
import { SETUP_PAGE, SetupPhases } from "./contract";
import { dockerAvailability } from "./docker";
import type { Environment } from "./environment";
import { meetsNodeRequirement, REQUIRED_NODE } from "./harness";
import { leftovers } from "./lifecycle";
import { lineSummary, LINES } from "./lines";
import { HARNESS_ROOT } from "./paths";
import { embeddedArchivePath, pluginJarPath, productVersion, serverJarPath } from "./product";
import { readState } from "./state";
import { TOPOLOGIES } from "./topologies/index";
import { errorMessage } from "./util";

function nodeReport(): Record<string, unknown> {
  return { version: process.versions.node, required: REQUIRED_NODE, ok: meetsNodeRequirement(process.versions.node) };
}

function productReport(): Record<string, unknown> {
  try {
    return {
      version: productVersion(),
      pluginJar: { path: pluginJarPath(), present: existsSync(pluginJarPath()) },
      serverJar: { path: serverJarPath(), present: existsSync(serverJarPath()) },
      embeddedArchive: { path: embeddedArchivePath(), present: existsSync(embeddedArchivePath()) },
      build: "./gradlew dist (repository root)",
    };
  } catch (error) {
    return { error: errorMessage(error) };
  }
}

export async function doctorReport(): Promise<Record<string, unknown>> {
  const docker = await dockerAvailability();
  const state = readState();
  return {
    node: nodeReport(),
    dependenciesInstalled: existsSync(join(HARNESS_ROOT, "node_modules", "mineflayer")),
    docker,
    product: productReport(),
    artifacts: await artifactStatuses(),
    environment: state ? { topology: state.topology, bootedAt: state.bootedAt } : null,
    leftovers: docker.ok ? await leftovers() : "docker unavailable",
    topologies: Object.fromEntries(Object.values(TOPOLOGIES).map((definition) => [definition.name, definition.description])),
    lines: Object.values(LINES).map(lineSummary),
  };
}

export async function instanceOverview(environment: Environment): Promise<Record<string, unknown>> {
  const instance = environment.instance;
  const setup = await instance.setupStatus().catch(() => null);
  const codeNeeded = setup !== null && setup.phase !== SetupPhases.ready;
  return {
    topology: environment.state.topology,
    panel: instance.baseUrl,
    healthy: await instance.isHealthy(),
    claimed: setup?.claimed ?? null,
    setupPhase: setup?.phase ?? null,
    setupPage: `${instance.baseUrl}${SETUP_PAGE}`,
    setupCode: codeNeeded ? await instance.latestSetupCode() : null,
    setupCodeFile: codeNeeded ? instance.setupCodeFilePath() : null,
    connectPending: environment.state.connectPending,
    ownerEmail: instance.ownerEmail,
    session: await instance.profile(),
    apiKeyKnown: instance.apiKey !== null,
  };
}
