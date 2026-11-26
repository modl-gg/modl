const SETTINGS = "/v1/panel/settings/ai";

export const AiEndpoints = {
  settings: SETTINGS,
  connection: (provider: string) => `${SETTINGS}/connections/${encodeURIComponent(provider)}`,
  models: `${SETTINGS}/models`,
  test: `${SETTINGS}/test`,
  moderation: "/v1/panel/settings/ai-moderation",
} as const;

export const AiErrors = {
  addressBlocked: "AI_ADDRESS_BLOCKED",
  httpPublic: "AI_BASE_URL_HTTP_PUBLIC",
  notReady: "AI_NOT_READY",
  managedByConfig: "AI_MANAGED_BY_CONFIG",
} as const;

export const AiFailures = {
  auth: "AI_FAILURE_AUTH",
  redirect: "AI_FAILURE_REDIRECT",
  invalidResponse: "AI_FAILURE_INVALID_RESPONSE",
  truncated: "AI_FAILURE_TRUNCATED",
  refused: "AI_FAILURE_REFUSED",
  hourlyBudget: "AI_FAILURE_HOURLY_BUDGET",
} as const;

export const AiOutputModes = {
  schema: "AI_OUTPUT_MODE_SCHEMA",
  jsonObject: "AI_OUTPUT_MODE_JSON_OBJECT",
} as const;
