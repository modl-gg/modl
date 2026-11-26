import { join } from "node:path";
import { HARNESS_ROOT, REPORTS_DIR } from "../lib/paths";

export const SHOTS_HOME = join(HARNESS_ROOT, ".shots");
export const SHOTS_RUNTIME = join(SHOTS_HOME, "runtime");
export const SHOTS_DATA = join(SHOTS_RUNTIME, "data");
export const SHOTS_CLOCK_DIR = join(SHOTS_RUNTIME, "clock");
export const SHOTS_IDENTITY = join(SHOTS_RUNTIME, "identity");
export const SHOTS_COMPOSE_FILE = join(SHOTS_RUNTIME, "compose.yml");
export const SHOTS_DATASETS = join(SHOTS_HOME, "datasets");
export const SHOTS_BASELINE = join(SHOTS_HOME, "baseline");
export const SHOTS_ASSETS = join(SHOTS_HOME, "assets");

export const REVIEW_DIR = join(REPORTS_DIR, "review");
export const REVIEW_CURRENT = join(REVIEW_DIR, "current");
export const REVIEW_DIFF = join(REVIEW_DIR, "diff");
export const REVIEW_STATE = join(REVIEW_DIR, "state.json");
export const REVIEW_FEEDBACK = join(REVIEW_DIR, "feedback.md");
