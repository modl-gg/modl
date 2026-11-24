import { defineConfig, normalizePath } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "path";
import { execFileSync } from "node:child_process";

interface VendorChunkRule {
  chunk: string;
  matches: (moduleId: string) => boolean;
}

const panelDir = import.meta.dirname;
const devBackendUrl = `http://localhost:${process.env["MODL_DEV_BACKEND_PORT"] ?? 18080}`;
const COMMIT_SHA = /^[0-9a-f]{40}$/;
const REPOSITORY_URL = /^https:\/\/[^\s/?#]+(\/[^\s/?#]+)+$/;

function gitHead(): string {
  try {
    return execFileSync("git", ["rev-parse", "HEAD"], { cwd: panelDir, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
  } catch {
    return "";
  }
}

function sourceCommit(): string {
  const commit = (process.env["MODL_SOURCE_COMMIT"] ?? gitHead()).toLowerCase();
  return COMMIT_SHA.test(commit) ? commit : "";
}

function sourceRepository(): string {
  const repository = (process.env["MODL_SOURCE_REPOSITORY"] ?? "").trim().replace(/\/+$/, "");
  return REPOSITORY_URL.test(repository) ? repository : "";
}

process.env["VITE_MODL_SOURCE_COMMIT"] = sourceCommit();
process.env["VITE_MODL_SOURCE_REPOSITORY"] = sourceRepository();

const REACT_CORE_MODULE = /\/node_modules\/(react|react-dom|react-is|scheduler)\//;

function fromAnyPackage(...packagePrefixes: string[]): (moduleId: string) => boolean {
  return (moduleId) => packagePrefixes.some((prefix) => moduleId.includes(`/node_modules/${prefix}`));
}

const VENDOR_CHUNK_RULES: readonly VendorChunkRule[] = [
  { chunk: "three-vendor", matches: fromAnyPackage("three/") },
  {
    chunk: "charts-vendor",
    matches: fromAnyPackage(
      "recharts",
      "victory-vendor",
      "d3-",
      "internmap",
      "immer",
      "reselect",
      "es-toolkit",
      "eventemitter3",
      "decimal.js-light",
      "tiny-invariant",
    ),
  },
  {
    chunk: "radix-vendor",
    matches: fromAnyPackage(
      "@radix-ui/",
      "@floating-ui/",
      "react-remove-scroll",
      "react-style-singleton",
      "aria-hidden",
      "use-callback-ref",
      "use-sidecar",
      "use-sync-external-store",
      "get-nonce",
      "detect-node-es",
      "tslib",
    ),
  },
  { chunk: "dnd-vendor", matches: fromAnyPackage("react-dnd", "dnd-core", "@react-dnd/", "redux/") },
  { chunk: "form-vendor", matches: fromAnyPackage("react-hook-form", "@hookform/", "zod") },
  { chunk: "i18n-vendor", matches: fromAnyPackage("i18next", "react-i18next") },
  { chunk: "date-vendor", matches: fromAnyPackage("date-fns") },
  { chunk: "react-vendor", matches: (moduleId) => REACT_CORE_MODULE.test(moduleId) },
  { chunk: "icons-vendor", matches: fromAnyPackage("lucide-react") },
  { chunk: "query-vendor", matches: fromAnyPackage("@tanstack/") },
];

const isOnDemandFont = fromAnyPackage("@fontsource-variable/");

function vendorChunkFor(moduleId: string): string | null {
  const normalizedId = normalizePath(moduleId);
  if (!normalizedId.includes("/node_modules/") || isOnDemandFont(normalizedId)) return null;
  return VENDOR_CHUNK_RULES.find((rule) => rule.matches(normalizedId))?.chunk ?? "vendor";
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    tsconfigPaths: true,
    dedupe: ["react", "react-dom", "@bufbuild/protobuf"],
  },
  root: path.resolve(panelDir, "client"),
  envDir: panelDir,
  build: {
    outDir: path.resolve(panelDir, "dist"),
    emptyOutDir: true,
    sourcemap: false,
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [{ name: vendorChunkFor, debugName: "vendor-chunks", includeDependenciesRecursively: false }],
        },
      },
    },
  },
  server: {
    port: 3000,
    proxy: {
      "/v1": {
        target: devBackendUrl,
        ws: true,
      },
    },
  },
});
