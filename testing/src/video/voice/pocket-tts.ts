import { existsSync } from "node:fs";
import { join } from "node:path";
import { runProcess } from "../../lib/process";
import { removePath, sha256OfFile } from "../../lib/util";
import { requirePinnedFile } from "../assets/pinned-file";
import { KYUTAI_REPOSITORY, KYUTAI_REVISION } from "./kyutai";
import type { SpeechEngine } from "./speech-engine";
import { TTS_HOME, type VoiceSetting } from "./voice";

export const POCKET_TTS = {
  pythonPackage: "pocket-tts==4.0.1",
  python: "3.12",
  dependenciesNotNewerThan: "2026-11-01T00:00:00Z",
  model: {
    repository: KYUTAI_REPOSITORY,
    revision: KYUTAI_REVISION,
    file: "languages/english/model.safetensors",
    sha256: "4c56cbd171c63e8a26ea61f5d48b8a0cd8f3c51f4652c0ab9a19bfcced8c931d",
    licence: "CC BY 4.0",
  },
  tokenizer: {
    repository: KYUTAI_REPOSITORY,
    revision: "00eac05ed3d16bdc3f6b5d598874019c34a89214",
    file: "languages/english/tokenizer.json",
    sha256: "f498428e1eafee50492f7be13dc9bfafcfc12e508cd0eb1b01c92ecd5d8c6687",
  },
} as const;

interface CachedHubFile {
  repository: string;
  revision: string;
  file: string;
  sha256: string;
}

const VERIFIED_HUB_FILES: readonly CachedHubFile[] = [POCKET_TTS.model, POCKET_TTS.tokenizer];

const GENERATE_TIMEOUT_MS = 900_000;
const SYSTEM_PATH = "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin";

function cachedPath(hubFile: CachedHubFile): string {
  return join(TTS_HOME, "hf", "hub", `models--${hubFile.repository.replace("/", "--")}`, "snapshots", hubFile.revision, hubFile.file);
}

function isolatedEnvironment(offline: boolean): string[] {
  return [
    `PATH=${process.env["PATH"] ?? SYSTEM_PATH}`,
    `HOME=${join(TTS_HOME, "home")}`,
    `XDG_CACHE_HOME=${join(TTS_HOME, "xdg")}`,
    `UV_CACHE_DIR=${join(TTS_HOME, "uv")}`,
    `UV_TOOL_DIR=${join(TTS_HOME, "uv-tools")}`,
    `UV_PYTHON_INSTALL_DIR=${join(TTS_HOME, "python")}`,
    "UV_PYTHON_PREFERENCE=only-managed",
    "UV_NO_CONFIG=1",
    `UV_EXCLUDE_NEWER=${POCKET_TTS.dependenciesNotNewerThan}`,
    `HF_HOME=${join(TTS_HOME, "hf")}`,
    "HF_HUB_DISABLE_TELEMETRY=1",
    ...(offline ? ["HF_HUB_OFFLINE=1"] : []),
  ];
}

export class PocketTts implements SpeechEngine {
  readonly id = `${POCKET_TTS.pythonPackage}<=${POCKET_TTS.dependenciesNotNewerThan}/${POCKET_TTS.model.revision}`;
  private modelVerified = false;
  private readonly verifiedVoices = new Map<string, string>();

  async speak(text: string, voice: VoiceSetting, output: string): Promise<void> {
    await this.verifyModel({ required: false });
    const voiceArgument = await this.voiceArgument(voice);
    const offline = this.modelVerified && voice.source.kind === "embedding";
    await runProcess(
      "env",
      [
        "-i",
        ...isolatedEnvironment(offline),
        "uvx", "--python", POCKET_TTS.python, POCKET_TTS.pythonPackage,
        "generate", "--quiet", "--voice", voiceArgument, "--text", text, "--output-path", output,
      ],
      { timeoutMs: GENERATE_TIMEOUT_MS },
    );
    try {
      await this.verifyModel({ required: true });
    } catch (error) {
      removePath(output);
      throw error;
    }
  }

  private async voiceArgument(voice: VoiceSetting): Promise<string> {
    if (voice.source.kind === "builtin") return voice.name;
    const verified = this.verifiedVoices.get(voice.source.file.sha256);
    if (verified !== undefined) return verified;
    const path = await requirePinnedFile(voice.source.file);
    this.verifiedVoices.set(voice.source.file.sha256, path);
    return path;
  }

  private async verifyModel(options: { required: boolean }): Promise<void> {
    if (this.modelVerified) return;
    for (const hubFile of VERIFIED_HUB_FILES) {
      const path = cachedPath(hubFile);
      if (!existsSync(path)) {
        if (options.required) throw new Error(`pocket-tts did not cache ${hubFile.file} at ${path}`);
        return;
      }
      const actual = await sha256OfFile(path);
      if (actual !== hubFile.sha256) throw new Error(`the cached ${path} has sha256 ${actual}, expected the pinned ${hubFile.sha256}`);
    }
    this.modelVerified = true;
  }
}
