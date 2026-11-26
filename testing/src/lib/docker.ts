import { runProcess, type CommandResult, type RunOptions } from "./process";
import { errorMessage } from "./util";
import { HARNESS_LANE } from "./paths";

export const HARNESS_LABEL = "gg.modl.testing";
export const LANE_LABEL = "gg.modl.testing.lane";
const LANE_VALUE = HARNESS_LANE ?? "interactive";

export function harnessLabels(): Record<string, string> {
  return { [HARNESS_LABEL]: "1", [LANE_LABEL]: LANE_VALUE };
}

export async function runDocker(args: string[], options: RunOptions = {}): Promise<CommandResult> {
  return runProcess("docker", args, options);
}

export type DockerAvailability = { ok: true; serverVersion: string; composeVersion: string } | { ok: false; error: string };

export async function dockerAvailability(): Promise<DockerAvailability> {
  try {
    const server = await runDocker(["version", "--format", "{{.Server.Version}}"], { timeoutMs: 20_000 });
    const composePlugin = await runDocker(["compose", "version", "--short"], { timeoutMs: 20_000 });
    return { ok: true, serverVersion: server.stdout.trim(), composeVersion: composePlugin.stdout.trim() };
  } catch (error) {
    return { ok: false, error: errorMessage(error) };
  }
}

export async function pullIfMissing(image: string, timeoutMs: number): Promise<void> {
  const present = await runDocker(["image", "inspect", image], { allowFailure: true });
  if (present.code !== 0) await runDocker(["pull", image], { timeoutMs });
}

export async function compose(project: string, file: string, args: string[], options?: RunOptions): Promise<CommandResult> {
  return runDocker(["compose", "-p", project, "-f", file, ...args], options);
}

export interface ContainerState {
  exists: boolean;
  status: string;
  exitCode: number | null;
  startedAt: string | null;
  oomKilled: boolean;
}

export async function containerState(container: string): Promise<ContainerState> {
  const result = await runDocker(
    ["inspect", "-f", "{{.State.Status}}|{{.State.ExitCode}}|{{.State.StartedAt}}|{{.State.OOMKilled}}", container],
    { allowFailure: true },
  );
  if (result.code !== 0) return { exists: false, status: "missing", exitCode: null, startedAt: null, oomKilled: false };
  const [status, exitCode, startedAt, oomKilled] = result.stdout.trim().split("|");
  return {
    exists: true,
    status: status ?? "unknown",
    exitCode: exitCode === undefined ? null : Number(exitCode),
    startedAt: startedAt ?? null,
    oomKilled: oomKilled === "true",
  };
}

export async function containerLogs(container: string, since?: string): Promise<string> {
  const args = ["logs", "--timestamps"];
  if (since) args.push("--since", since);
  args.push(container);
  const result = await runDocker(args, { allowFailure: true, timeoutMs: 60_000 });
  return `${result.stdout}${result.stderr}`;
}

export async function execInContainer(container: string, command: string[]): Promise<CommandResult> {
  return runDocker(["exec", container, ...command], { timeoutMs: 30_000 });
}

export async function readContainerFile(container: string, path: string): Promise<string | null> {
  const result = await runDocker(["exec", container, "cat", path], { allowFailure: true, timeoutMs: 30_000 });
  return result.code === 0 ? result.stdout : null;
}

export async function killContainer(container: string): Promise<CommandResult> {
  return runDocker(["kill", "--signal", "KILL", container], { allowFailure: true });
}

export async function startContainer(container: string): Promise<CommandResult> {
  return runDocker(["start", container]);
}

export async function stopContainer(container: string, timeoutSeconds: number): Promise<CommandResult> {
  return runDocker(["stop", "--time", String(timeoutSeconds), container], {
    allowFailure: true,
    timeoutMs: (timeoutSeconds + 30) * 1000,
  });
}

export interface ProjectResources {
  containers: string[];
  networks: string[];
  volumes: string[];
}

async function listByLabel(kind: "ps" | "network" | "volume", label: string): Promise<string[]> {
  const args =
    kind === "ps"
      ? ["ps", "-a", "--filter", `label=${label}`, "--format", "{{.Names}}"]
      : [kind, "ls", "--filter", `label=${label}`, "--format", "{{.Name}}"];
  const result = await runDocker(args, { allowFailure: true });
  return result.stdout.split("\n").map((line) => line.trim()).filter(Boolean);
}

export async function projectResources(project: string): Promise<ProjectResources> {
  const label = `com.docker.compose.project=${project}`;
  const [containers, networks, volumes] = await Promise.all([
    listByLabel("ps", label),
    listByLabel("network", label),
    listByLabel("volume", label),
  ]);
  return { containers, networks, volumes };
}

export async function harnessOneShotContainers(): Promise<string[]> {
  return listByLabel("ps", `${LANE_LABEL}=${LANE_VALUE}`);
}

export async function forceRemoveProject(project: string, options: { keepVolumes: boolean }): Promise<void> {
  const resources = await projectResources(project);
  if (resources.containers.length > 0) await runDocker(["rm", "-f", "-v", ...resources.containers], { allowFailure: true });
  const oneShots = await harnessOneShotContainers();
  if (oneShots.length > 0) await runDocker(["rm", "-f", ...oneShots], { allowFailure: true });
  for (const network of resources.networks) await runDocker(["network", "rm", network], { allowFailure: true });
  if (!options.keepVolumes) {
    for (const volume of resources.volumes) await runDocker(["volume", "rm", "-f", volume], { allowFailure: true });
  }
}

export interface OneShotRun {
  image: string;
  user: string;
  workdir: string;
  volumes: string[];
  environment?: Record<string, string>;
  command: string[];
  timeoutMs: number;
}

export async function runOneShot(run: OneShotRun): Promise<CommandResult> {
  const args = ["run", "--rm", "--label", `${HARNESS_LABEL}=1`, "--label", `${LANE_LABEL}=${LANE_VALUE}`, "--user", run.user, "-w", run.workdir];
  for (const volume of run.volumes) args.push("-v", volume);
  for (const [key, value] of Object.entries(run.environment ?? {})) args.push("-e", `${key}=${value}`);
  args.push(run.image, ...run.command);
  return runDocker(args, { timeoutMs: run.timeoutMs });
}

export interface Sidecar {
  name: string;
  image: string;
  network: string;
  alias: string;
  user: string;
  volumes: string[];
  environment: Record<string, string>;
  command: string[];
  tmpfs?: readonly string[];
  memory?: string;
}

export async function startSidecar(sidecar: Sidecar): Promise<void> {
  await removeSidecar(sidecar.name);
  const args = ["run", "-d", "--rm", "--name", sidecar.name, "--label", `${HARNESS_LABEL}=1`, "--label", `${LANE_LABEL}=${LANE_VALUE}`, "--user", sidecar.user];
  args.push("--network", sidecar.network, "--network-alias", sidecar.alias, "--read-only", "--cap-drop", "ALL");
  for (const volume of sidecar.volumes) args.push("-v", volume);
  for (const mount of sidecar.tmpfs ?? []) args.push("--tmpfs", mount);
  if (sidecar.memory !== undefined) args.push("--memory", sidecar.memory, "--memory-swap", sidecar.memory);
  for (const [key, value] of Object.entries(sidecar.environment)) args.push("-e", `${key}=${value}`);
  args.push(sidecar.image, ...sidecar.command);
  await runDocker(args, { timeoutMs: 180_000 });
}

export async function removeSidecar(name: string): Promise<void> {
  await runDocker(["rm", "-f", name], { allowFailure: true });
}
