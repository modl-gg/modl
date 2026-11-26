import type { ArtifactId } from "../artifacts";
import type { ServerConsole } from "../console";
import type { InstanceClient } from "../instance";
import type { Logger } from "../log";
import type { ContainerLog, LogMark } from "../logs";
import type { MemoryProfile } from "../settings";
import type { Awaitable } from "../util";
import type { DataMount, EnvironmentState, ServerKind, ServerRole, TopologyName, UpOptions } from "../state";

export interface PortPlan {
  game?: number;
  rcon?: number;
  http?: number;
}

export interface ServerSpec {
  name: string;
  kind: ServerKind;
  role: ServerRole;
  service: string;
  memory: MemoryProfile | null;
  ports: PortPlan;
  dataMount?: DataMount;
}

export interface PluginResources {
  connection: string;
  config: string;
  serverFeatures: string;
}

export interface PrepareContext {
  state: EnvironmentState;
  resources: PluginResources;
  artifactPath(id: ArtifactId): string;
  logger: Logger;
}

export type ModlReadiness = "core" | "member" | "none";

export interface BootContext {
  state: EnvironmentState;
  logger: Logger;
  instance: InstanceClient;
  console(server: string): ServerConsole;
  log(server: string): ContainerLog;
  mark(server: string): Promise<LogMark>;
  composeUp(services: string[], options?: { build?: boolean }): Promise<void>;
  stopGracefully(server: string): Promise<void>;
  startExisting(server: string): Promise<void>;
  waitForMinecraft(server: string, since: LogMark, readiness: ModlReadiness): Promise<number>;
  waitForPanel(): Promise<number>;
  save(): void;
}

export interface BootStep {
  step: string;
  seconds: number;
  detail?: unknown;
}

export interface TopologyDefinition {
  name: TopologyName;
  description: string;
  panelPort: number;
  servers(options: UpOptions): ServerSpec[];
  roles: { entry: string; core: string; data: string; notices: string };
  artifacts(options: UpOptions): ArtifactId[];
  needsServerJar(options: UpOptions): boolean;
  expectedBootProblems: readonly RegExp[];
  prepare(context: PrepareContext): Awaitable<void>;
  compose(context: PrepareContext): string;
  boot(context: BootContext): Promise<BootStep[]>;
  connect(context: BootContext): Awaitable<BootStep[]>;
}
