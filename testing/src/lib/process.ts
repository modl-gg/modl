import { spawn } from "node:child_process";
import { basename } from "node:path";
import { cancellationError, currentSignal } from "./cancellation";

export interface CommandResult {
  code: number;
  stdout: string;
  stderr: string;
}

export interface RunOptions {
  allowFailure?: boolean;
  timeoutMs?: number;
  input?: string;
  cwd?: string;
}

const DEFAULT_TIMEOUT_MS = 120_000;
const FAILURE_DETAIL_CHARS = 3000;

export class ProcessError extends Error {}

export async function runProcess(command: string, args: string[], options: RunOptions = {}): Promise<CommandResult> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const label = `${basename(command)} ${args.join(" ")}`;
  const signal = currentSignal();
  if (signal?.aborted) return Promise.reject(cancellationError(signal));
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      stdio: ["pipe", "pipe", "pipe"],
      ...(options.cwd === undefined ? {} : { cwd: options.cwd }),
      ...(signal ? { signal } : {}),
    });
    const stdout: Buffer[] = [];
    const stderr: Buffer[] = [];
    const timer = setTimeout(() => child.kill("SIGKILL"), timeoutMs);
    child.stdout.on("data", (chunk: Buffer) => stdout.push(chunk));
    child.stderr.on("data", (chunk: Buffer) => stderr.push(chunk));
    child.once("error", (error) => {
      clearTimeout(timer);
      if (signal?.aborted) reject(cancellationError(signal));
      else reject(new ProcessError(`${label} failed to start: ${error.message}`));
    });
    child.once("close", (code) => {
      clearTimeout(timer);
      if (signal?.aborted) {
        reject(cancellationError(signal));
        return;
      }
      const result = {
        code: code ?? -1,
        stdout: Buffer.concat(stdout).toString("utf8"),
        stderr: Buffer.concat(stderr).toString("utf8"),
      };
      if (result.code !== 0 && !options.allowFailure) {
        const detail = (result.stderr || result.stdout).trim().slice(-FAILURE_DETAIL_CHARS);
        reject(new ProcessError(`${label} exited ${result.code}: ${detail}`));
        return;
      }
      resolve(result);
    });
    child.stdin.end(options.input ?? "");
  });
}
