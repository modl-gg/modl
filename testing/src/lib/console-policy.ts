import { leakedPackages, type LeakIndex } from "./console-leaks";
import {
  CompatibilityMessages,
  CONFIG_REJECTED,
  ConsolePatterns,
  DatabaseMoveMessages,
  HostedImportMessages,
  IMPORT_SOURCE,
  ImportMessages,
  ProxyCommandMessages,
  StartupFailurePatterns,
  UpdateMessages,
} from "./contract";
import { entryText, type ConsoleEntry } from "./log-entries";
import { timestampKey } from "./logs";

export interface PatternRule {
  id: string;
  pattern: RegExp;
}

export const BENIGN_PROBLEMS: readonly PatternRule[] = [
  { id: "paper-version-check", pattern: /\[PaperVersionFetcher\]|Error obtaining version information/i },
  { id: "packetevents-update-check", pattern: /\[packetevents\][^\n]*(?:Failed to check for updates|Failed to parse packetevents version|There is an update available|You are running a development build)/ },
  { id: "packetevents-update-checker-failure", pattern: /com\.github\.retrooper\.packetevents\.util\.updatechecker\.UpdateChecker/ },
  { id: "bstats", pattern: /bStats/i },
  { id: "spawn-biome", pattern: /Unable to find spawn biome/ },
  { id: "sqlite-native-access", pattern: ConsolePatterns.sqliteNativeAccessWarning },
  { id: "jvm-native-access", pattern: /terminally deprecated|sun\.misc\.Unsafe|--enable-native-access|restricted method in java\.lang/ },
];

const NATIVE_ACCESS_WARNINGS = new RegExp(ConsolePatterns.sqliteNativeAccessWarning.source, "g");
const EXCEPTION_MARKER = /Exception|Error:|^\s+at [\w.$<>]+\(|^Caused by:/m;

export interface ConsoleProblem {
  server: string;
  ts: string;
  level: string;
  message: string;
  trace: string[];
}

const SECTION_SIGN = "\u00a7";
const CONSOLE_TIMESTAMP = /^\s*(?:\[\d{2}:\d{2}:\d{2} [A-Z]+\]:?|\d{2}:\d{2}:\d{2} \[[A-Z]+\]) /;
const MODL_TAG = new RegExp(`${CONSOLE_TIMESTAMP.source}\\[(?:modl|gg\\.modl\\.[\\w.$]+)\\]:? `);

export function isModlEntry(entry: ConsoleEntry): boolean {
  return MODL_TAG.test(entry.lines[0] ?? "");
}

const SERVER_KICK_LOG = new RegExp(
  `${CONSOLE_TIMESTAMP.source}(?:\\S+ lost connection: |Disconnecting .+: |\\[connected player\\] .+ has disconnected: |\\[\\S+\\] disconnected with: )`,
);

function isServerKickLog(entry: ConsoleEntry): boolean {
  return !isModlEntry(entry) && SERVER_KICK_LOG.test(entry.lines[0] ?? "");
}

export function hasRawFormatting(entry: ConsoleEntry): boolean {
  return entryText(entry).includes(SECTION_SIGN) && !isServerKickLog(entry);
}

function isProblem(entry: ConsoleEntry): boolean {
  if (entry.level === "ERROR" || entry.level === "FATAL" || hasRawFormatting(entry)) return true;
  return entry.level === "WARN" && EXCEPTION_MARKER.test(entryText(entry));
}

const UNCAUGHT_EXCEPTION_HEADER = /Caught previously unhandled exception :\s*$/;
const REPORT_FOLLOWS_HEADER_WITHIN_NS = 1_000_000_000n;

function reportDelay(header: ConsoleEntry, report: ConsoleEntry): bigint {
  return timestampKey(report.ts) - timestampKey(header.ts);
}

function isReportOfHeader(header: ConsoleEntry, report: ConsoleEntry | undefined): report is ConsoleEntry {
  return report !== undefined
    && UNCAUGHT_EXCEPTION_HEADER.test(header.lines[0] ?? "")
    && report.level === "ERROR"
    && reportDelay(header, report) >= 0n
    && reportDelay(header, report) <= REPORT_FOLLOWS_HEADER_WITHIN_NS;
}

function withUncaughtHeadersFolded(entries: readonly ConsoleEntry[]): ConsoleEntry[] {
  return entries.filter((entry, index) => !isReportOfHeader(entry, entries[index + 1]));
}

export function scanProblems(server: string, entries: readonly ConsoleEntry[], ignore: readonly RegExp[] = []): ConsoleProblem[] {
  const skipped = [...BENIGN_PROBLEMS.map((rule) => rule.pattern), ...ignore];
  return withUncaughtHeadersFolded(entries)
    .filter(isProblem)
    .filter((entry) => !skipped.some((pattern) => pattern.test(entryText(entry))))
    .map((entry) => ({
      server,
      ts: entry.ts,
      level: entry.level,
      message: entry.lines[0] ?? "",
      trace: entry.lines.slice(1, 16),
    }));
}

const LIBRARY_OUTPUT_SIGNATURES: readonly PatternRule[] = [
  { id: "slf4j-provider", pattern: /SLF4J\([A-Z]\):|SLF4J: / },
  { id: "logback-default-pattern", pattern: /\d{2}:\d{2}:\d{2}\.\d{3} \[[^\]]+\] (?:TRACE|DEBUG|INFO|WARN|ERROR)\s+[\w.$]+ -- / },
  { id: "spring-banner", pattern: /:: Spring Boot ::|\/\\\\ \/ ___'_ __ _ _\(_\)_ __/ },
];

export const EXPECTED_MODL_LINES: readonly PatternRule[] = [
  { id: "plugin-lifecycle", pattern: /(?:Loading(?: server plugin)?|Enabling|Disabling) modl v\d/ },
  { id: "plugin-booted", pattern: ConsolePatterns.pluginBooted },
  { id: "member-booted", pattern: ConsolePatterns.memberBooted },
  { id: "server-libraries-installed", pattern: ConsolePatterns.serverLibrariesInstalled },
  { id: "server-libraries-download", pattern: ConsolePatterns.serverLibrariesDownload },
  { id: "panel-archive-download", pattern: ConsolePatterns.panelArchiveDownload },
  { id: "replay-budget-full", pattern: /\[replay\] The replay memory budget is full, so new recordings start only once it has room again/ },
  { id: "small-heap", pattern: /This server has a maximum heap of \d+ MiB\. The built-in panel shares it/ },
  { id: "panel-waiting", pattern: /Waiting for the built-in panel to start \(\d+s\)/ },
  { id: "panel-started", pattern: /The built-in panel is listening on port \d+(?: for \S+)?\. It opens only if your host forwards port \d+ to this server|The built-in panel is listening on port \d+ of this machine only/ },
  { id: "game-host-port", pattern: ConsolePatterns.gameHostPortHint },
  { id: "member-waits-for-key", pattern: ConsolePatterns.memberWaitsForKey },
  { id: "member-found-key", pattern: ConsolePatterns.memberFoundKey },
  { id: "panel-unreachable", pattern: ConsolePatterns.panelUnreachable },
  { id: "panel-other-service", pattern: ConsolePatterns.panelOtherService },
  { id: "tunnel-connected", pattern: ConsolePatterns.tunnelConnected },
  { id: "tunnel-not-connected", pattern: ConsolePatterns.tunnelNotConnected },
  { id: "tunnel-cannot-start", pattern: ConsolePatterns.tunnelCannotStart },
  { id: "panel-loopback-unreachable", pattern: ConsolePatterns.panelLoopbackUnreachable },
  { id: "storage-move-stopped", pattern: ConsolePatterns.storageMoveStopped },
  { id: "storage-moved", pattern: ConsolePatterns.storageMoved },
  { id: "storage-old-copies-removed", pattern: ConsolePatterns.storageOldCopiesRemoved },
  { id: "storage-old-copies-not-removed", pattern: ConsolePatterns.storageOldCopiesNotRemoved },
  { id: "storage-panel-choice-kept", pattern: ConsolePatterns.storagePanelChoiceKept },
  { id: "setup-code", pattern: ConsolePatterns.setupCode },
  { id: "setup-code-file", pattern: new RegExp(`${ConsolePatterns.setupCodeFile.source}|${ConsolePatterns.setupConfigFallback.source}`) },
  { id: "api-key-generated", pattern: /API key generated\. View it in Panel > Settings > General > Minecraft servers\./ },
  { id: "panel-url-unset", pattern: /The Panel URL is not set, so in-game and email links point to \S+\. / },
  { id: "console-email", pattern: /(?:Login code|Email change code|Staff invitation) for \S+: / },
  { id: "login-code-lockout", pattern: /Codes are not sent to \S+ for the next \d+ (?:second|minute|hour)s?: too many incorrect codes were entered for this address\./ },
  { id: "database-lock-wait", pattern: ConsolePatterns.databaseLockWait },
  {
    id: "database-lock-lost",
    pattern: new RegExp(`${ConsolePatterns.databaseLockLost.source}|${ConsolePatterns.databaseRetainedLockLost.source}`),
  },
  { id: "database-disk-full", pattern: ConsolePatterns.databaseDiskFull },
  { id: "database-durability", pattern: ConsolePatterns.databaseDurability },
  { id: "database-move", pattern: new RegExp(Object.values(DatabaseMoveMessages).map((pattern) => pattern.source).join("|")) },
  { id: "restore-uploads-pending", pattern: ConsolePatterns.restoreUploadsPending },
  { id: "database-not-incremental", pattern: ConsolePatterns.databaseNotIncremental },
  { id: "nightly-backup-failed", pattern: /The nightly database backup to \S+ failed: / },
  { id: "nightly-backup-made-room", pattern: /Disk space is low: removed \d+ older nightly backup\(s\) in \S+ to make room for the new one/ },
  { id: "server-start-failed", pattern: /The modl server failed to start: .+ \(details in \S+\)/ },
  { id: "backend-connected", pattern: /Connected to the modl server at \S+/ },
  { id: "member-mode-detected", pattern: ConsolePatterns.memberModeDetected },
  { id: "notice-separator", pattern: StartupFailurePatterns.separator },
  { id: "config-rejected", pattern: CONFIG_REJECTED },
  {
    id: "startup-failure",
    pattern: new RegExp(
      [StartupFailurePatterns.reason, StartupFailurePatterns.remedy, StartupFailurePatterns.consequence, StartupFailurePatterns.coreFailure]
        .map((pattern) => pattern.source)
        .join("|"),
      "m",
    ),
  },
  {
    id: "backend-unreachable",
    pattern: /Failed to connect to the modl server at \S+, retrying in |The modl server at \S+ rejected the API key, retrying in /,
  },
  { id: "packet-interception", pattern: /modl packet interception is (?:on|off): / },
  {
    id: "realtime-origin-refused",
    pattern: /Refused a panel live-update connection from \S+\. The panel URL is \S+: open the panel there, or change the Panel URL in Panel > Settings > General > Panel\./,
  },
  { id: "panel-stop-timeout", pattern: /The built-in panel did not stop within \d+ seconds\./ },
  { id: "unreadable-local-config", pattern: /Ignoring \S+ for the login message because it could not be read: |Could not read the locale from config\.yml, using \S+: / },
  { id: "remote-url-override", pattern: /remote\.url overridden to \S+ \(via / },
  { id: "plain-http-notice", pattern: ConsolePatterns.plainHttpNotice },
  { id: "player-addresses-not-forwarded", pattern: ConsolePatterns.playerAddressesNotForwarded },
  { id: "login-invalid-name", pattern: ConsolePatterns.loginInvalidName },
  { id: "login-check-overridden", pattern: ConsolePatterns.loginCheckOverridden },
  { id: "keyless-lookup-limit", pattern: ConsolePatterns.keylessLookupLimitReached },
  { id: "member-players-can-join", pattern: ConsolePatterns.memberPlayersCanJoin },
  {
    id: "member-network-status",
    pattern: new RegExp(
      [
        ConsolePatterns.memberNoHub,
        ConsolePatterns.memberRealtimeDisabled,
        ConsolePatterns.memberDegraded,
        ConsolePatterns.memberNetworkConnected,
        ConsolePatterns.memberNetworkLost,
        ConsolePatterns.memberWebsocketBlocked,
        ConsolePatterns.memberKeyRevoked,
        ConsolePatterns.memberSessionReplaced,
        ProxyCommandMessages.disabledNotice,
      ]
        .map((pattern) => pattern.source)
        .join("|"),
    ),
  },
  { id: "update-notice", pattern: /modl \S+ is available \(this plugin is \S+\)\.|Release notes: https?:\/\/\S+/ },
  { id: "updates", pattern: new RegExp(Object.values(UpdateMessages).map((pattern) => pattern.source).join("|")) },
  {
    id: "version-compatibility",
    pattern: new RegExp(
      [
        CompatibilityMessages.mismatch,
        CompatibilityMessages.pluginOlder,
        CompatibilityMessages.serverOlder,
        CompatibilityMessages.unknown,
        CompatibilityMessages.acceptedAgain,
      ]
        .map((pattern) => pattern.source)
        .join("|"),
    ),
  },
  { id: "staff-2fa-verified", pattern: /\[Sync\] Staff 2FA verified for / },
  {
    id: "plugin-import-refusals",
    pattern: new RegExp(
      [ImportMessages.localConsoleOnly, ImportMessages.liteBansMissing, ImportMessages.sourceMissing, ImportMessages.sourceLocked, ImportMessages.choicesRequired]
        .map((pattern) => pattern.source)
        .join("|"),
    ),
  },
  {
    id: "plugin-import-preview",
    pattern: new RegExp(
      [
        /Usage: \/modl import <source>, then |Found here: [a-z, ]+\. Supported: |No data of a supported plugin was found here\. Supported: |\S+ is not a supported source\. Supported: /.source,
        `${IMPORT_SOURCE} has (?:\\d+ bans, \\d+ mutes, \\d+ warnings, \\d+ kicks and \\d+ notes for \\d+ players\\.|no players or punishments to import\\.|too many players for one import)`,
        /Not imported: \d+ punishments without a player account\.|\d+ IP-wide and \d+ server-scoped punishments\. widen: |\d+ IP history entries \(about \d+ MB\) are older than \d+ days\. keep: |An earlier import keeps old IP history\.|Back up modl first\. Imported records stay |To start, run within 60 seconds: |Nothing is left to import with these choices\.|Finish the modl setup in the panel first\.|An earlier import's kept IP history is still being released\.|Uploaded files are being moved to another storage\.|An import check or import is already running here\.|An import started on .+ is running\. See |Run \/modl import [a-z]+ first, then confirm within 60 seconds\./.source,
      ].join("|"),
    ),
  },
  {
    id: "plugin-import-run",
    pattern: new RegExp(
      [
        `${IMPORT_SOURCE} import (?:started \\(|completed(?: with problems)?: |started from .+ with \\S+ and \\S+\\.)|Reading ${IMPORT_SOURCE}: \\d+ of \\d+ players\\.`,
        /Sending the export to modl\.|modl (?:received the export and is importing it|is receiving the export)\.|The export is waiting to be sent\.|Importing: \d+ of \d+ players, \d+ skipped\.|No import (?:has run yet|is running)\.|Stopping the import\.|Records that were already imported stay\.|The modl server no longer knows this import\./.source,
      ].join("|"),
    ),
  },
  {
    id: "plugin-import-endings",
    pattern: new RegExp(
      [
        `The ${IMPORT_SOURCE} export failed\\.|${IMPORT_SOURCE} data could not be read: |${ImportMessages.sourceReconnectFailed.source}|No player could be read from ${IMPORT_SOURCE}: |There is not enough free disk space to copy the ${IMPORT_SOURCE} database: `,
        /The import (?:was cancelled\.|stopped reporting and timed out\.|was interrupted by a restart\.|failed: |request failed: )|Sending the export failed\.|The export is larger than the import limit\.|modl could not (?:read the export|be reached): |The export file could not be written: |Something in front of modl \(a reverse proxy or CDN\) refused the file|The export could not be sent to modl: /.source,
      ].join("|"),
    ),
  },
  {
    id: "plugin-import-diagnostics",
    pattern: new RegExp(
      `${ConsolePatterns.importUnreadablePlayer.source}|(?:Could not read the import folder|Could not delete the import file) |The (?:${IMPORT_SOURCE} )?import failed`,
    ),
  },
  {
    id: "hosted-import",
    pattern: new RegExp(
      [HostedImportMessages.started, HostedImportMessages.resumed, HostedImportMessages.paused, HostedImportMessages.finished]
        .map((pattern) => pattern.source)
        .join("|"),
    ),
  },
  {
    id: "network-staff-actions",
    pattern:
      /\[network\] (?:Running \/\S+ for .+ console$|Refused \/\S+ from .+ console: only modl commands run on the proxy\.|Refused a staff action from .+: \S+ is on .+\. Name the server key or server-name after this server's name in the proxy config\.|.+ is not a server name in the proxy config \(known: [^)]*\); staff hotbar actions from it are refused until the names match\.)/m,
  },
  {
    id: "anticheat-operations",
    pattern: /\[anticheat\] (?:Creating report ticket for |Report ticket created: |Replay captured for auto-report: )/,
  },
  {
    id: "backend-server-operations",
    pattern: /Hooked into \S+|Forwarded \/\S+ to the proxy\.|Auto-report triggered for |Dispatching command: |Executed \d+ stat-wipe command\(s\) for /,
  },
  {
    id: "server-features-server-name",
    pattern: /\[server-features\] server-name is empty in server-features\.yml; set it to this server's name in the proxy config so staff hotbar actions work\./,
  },
  {
    id: "legacy-upgrade-notice",
    pattern:
      /modl is now self-hosted\. \S+ was converted to |This is a fresh instance: data from your former modl\.gg panel is not loaded\.|Set remote\.url to the address of your new panel and remote\.api-key to a server key from /,
  },
  { id: "config-options-added", pattern: /\[config\] Added \d+ new option\(s\) to / },
  { id: "legacy-config-migration", pattern: /Removed migrated GUI sections from config\.yml|Migrated (?:punish GUI config|report_gui) from config\.yml to / },
  { id: "server-log-summary", pattern: /\d+ warnings\/errors, see \S+/ },
  { id: "signed-velocity-standalone", pattern: /\[SignedVelocity\] Using standalone SignedVelocity plugin/ },
];

export const SERVER_NAGS_ABOUT_MODL: readonly PatternRule[] = [
  { id: "player-login-event", pattern: /listening to the PlayerLoginEvent[^\n]*\bmodl\b/ },
  { id: "legacy-api-version", pattern: /Legacy plugin modl\b/ },
  { id: "nag-author", pattern: /Nag author\(s\)[^\n]*\bmodl\b/ },
];


export interface ConsoleFinding {
  server: string;
  ts: string;
  rule?: string;
  text: string;
}

export interface ConsoleAudit {
  noise: ConsoleFinding[];
  serverNags: ConsoleFinding[];
  rawFormatting: ConsoleFinding[];
  unexpectedModl: ConsoleFinding[];
  expectedModl: ConsoleFinding[];
}

function noiseRule(index: LeakIndex, text: string): string | null {
  const signature = LIBRARY_OUTPUT_SIGNATURES.find((rule) => rule.pattern.test(text));
  if (signature) return signature.id;
  const packages = leakedPackages(index, text.replace(NATIVE_ACCESS_WARNINGS, ""));
  return packages.length > 0 ? `server-packages: ${packages.join(", ")}` : null;
}

export function auditConsole(server: string, entries: readonly ConsoleEntry[], leaks: LeakIndex): ConsoleAudit {
  const audit: ConsoleAudit = { noise: [], serverNags: [], rawFormatting: [], unexpectedModl: [], expectedModl: [] };
  for (const entry of entries) {
    const text = entryText(entry);
    const excerpt = entry.lines.slice(0, 4).join("\n");
    const noise = noiseRule(leaks, text);
    if (noise) audit.noise.push({ server, ts: entry.ts, rule: noise, text: excerpt });
    if (hasRawFormatting(entry)) {
      audit.rawFormatting.push({ server, ts: entry.ts, text: excerpt });
    }
    if (!isModlEntry(entry)) {
      const nag = SERVER_NAGS_ABOUT_MODL.find((rule) => rule.pattern.test(text));
      if (nag) audit.serverNags.push({ server, ts: entry.ts, rule: nag.id, text: excerpt });
      continue;
    }
    const expected = EXPECTED_MODL_LINES.find((rule) => rule.pattern.test(text));
    if (expected) audit.expectedModl.push({ server, ts: entry.ts, rule: expected.id, text: excerpt });
    else audit.unexpectedModl.push({ server, ts: entry.ts, text: excerpt });
  }
  return audit;
}
