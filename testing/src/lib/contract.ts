import type { MinecraftKind } from "./state";
import { escapeRegExp } from "./util";

export const IMPORT_SOURCE_NAMES = ["LiteBans", "AdvancedBan", "LibertyBans", "BanManager", "BanSystem", "Vanilla and EssentialsX"] as const;

export const IMPORT_SOURCE = `(?:${IMPORT_SOURCE_NAMES.join("|")})`;

export const ConsolePatterns = {
  paperReady: /Done \([\d.,]+s\)! For help/,
  velocityReady: /Done \([\d.,]+s\)!/,
  bungeecordReady: /Listening on \/\S+:\d+/,
  pluginBooted: /modl is running \((?:Spigot|Velocity|BungeeCord)\)\./,
  memberBooted: /modl is running \(Spigot, behind-proxy mode\)\./,
  bukkitReloadComplete: /Reload complete\./,
  commandWhileStarting: /Connection to panel failed\. Please try again in a moment\./,
  modlVersionReply: /modl v\S+ - Moderation and Support Management System/,
  pluginFailed: /Error occurred while enabling modl|Could not load (?:plugin )?'[^']*modl[^']*'|modl is not running: /i,
  missingPacketEvents: /modl is not running: PacketEvents is not installed, but modl needs PacketEvents \S+ or newer\./,
  packetEventsRemedy: /Install PacketEvents \S+ or newer and restart the server\./,
  panelPortInUse: /Port (\d+) is already in use, so the built-in panel cannot start/,
  panelPortIsGamePort: /embedded\.port is (\d+), the port players join on, so the built-in panel would take it/,
  embeddedNeedsJava: /modl is not running: The built-in panel needs Java (\d+) or newer\. Update Java or use mode: remote\./,
  panelStarted: /The built-in panel is listening on port (\d+)/,
  gameHostPortHint: /This server runs on a game host(?: \(its game port is \d+\))?, which only forwards the ports it allocates to the server\. Add an allocation for port \d+ in the host panel's Network tab, or set embedded\.port in \S+ to an extra port it already allocated\./,
  memberWaitsForKey:
    /\[network\] This server has no server key yet \(remote\.api-key in \S+ is empty\), so modl stays idle\. Players can still join; the proxy enforces bans and mutes\. Create a key in Panel > Settings > General > Minecraft servers > Add server, choose Behind a proxy, paste it into remote\.api-key, and run \/modlserver reload\./,
  memberFoundKey: /\[network\] Found a server key in remote\.api-key; connecting to the modl server at \S+\./,
  panelUnreachable: /This server could not open the panel at \S+\. If you cannot open it in your browser either, the port is not forwarded to this server or the tunnel is down; see \S+\. Some networks cannot reach their own public address, so if the page opens for you, ignore this\./,
  panelLoopbackUnreachable:
    /This server could not open the panel at \S+\. Nothing answers at that address on this machine; start the panel, or the proxy in front of it, or fix the Panel URL\./,
  storageMoveStopped:
    /Moving the uploaded files to (?:S3|local) storage stopped(?: with errors\.|\.|: \d+ file\(s\) could not be copied \(.*\)\. The files are still served from both places\.) Open Panel > Settings > System > Storage to retry\./,
  storageMoved:
    /Moved the uploaded files to (?:S3|local) storage \(\d+ copied, \d+ already there\)\. The old copies were kept and deletes still reach them; remove them in Panel > Settings > System > Storage once you no longer need them\./,
  storageOldCopiesRemoved: /Removed the old copies in (?:S3|local) storage \(\d+ file\(s\)\)\./,
  storageOldCopiesNotRemoved:
    /Could not remove the old copies in (?:S3|local) storage: .+\. Open Panel > Settings > System > Storage to try again\.|Removing the old copies of the uploaded files failed\. Open Panel > Settings > System > Storage to try again\./,
  storagePanelChoiceKept:
    /MODL_STORAGE selects (?:S3|local) storage, but files uploaded earlier are still in the storage chosen in the panel\. They stay readable from there; to move them, remove MODL_STORAGE, move the files in Panel > Settings > System > Storage, then set it again\./,
  tunnelConnected: /Cloudflare Tunnel connected: https:\/\/\S+/,
  tunnelNotConnected:
    /Cloudflare Tunnel is not connected after 1 minute\. Check the tunnel token \(MODL_TUNNEL_TOKEN or connection\.yml embedded\.tunnel\.token\) and that this host allows outbound connections to Cloudflare on port 7844; modl keeps retrying\./,
  tunnelCannotStart: /Cloudflare Tunnel cannot start: .+\. (?:modl keeps retrying\.|Run cloudflared yourself instead, see \S+\.)/,
  panelOtherService: /\S+ answers, but not with this panel: the address or port points at another server or service\. Fix the Panel URL or the port forwarding; see \S+\./,
  serverLibrariesDownload:
    /Downloading modl server \S+ libraries from Maven Central: \d+ files, [\d.]+ MB\.|Downloading modl server libraries: \d+ of \d+ files, [\d.]+ of [\d.]+ MB\./,
  panelArchiveDownload:
    /Downloading the built-in panel \(modl-embedded-\S+\.zip, [\d.]+ MB\) from \S+\.|Downloading the built-in panel: [\d.]+ of [\d.]+ MB\./,
  panelArchiveMismatch: /\S+modl-embedded-\S+\.zip does not match this modl plugin jar \(/,
  serverLibrariesInstalled: /Installed modl server \S+ libraries to \S+(?: \(downloaded \d+ files, [\d.]+ MB, in \d+s\))?/,
  setupCode: /(?:Setup|Owner sign-in) code: ([A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4})\. Open (.+?\/setup) and enter it \(valid 1 hour\)\./,
  setupCodeFile: /The code is also saved in (\S+)\./,
  setupConfigFallback: /If this console is unreliable, stop the server, set /,
  memberModeDetected: /Proxy forwarding is enabled on this server \([^)]*\), so \S+ was created with mode: behind-proxy\./,
  networkProxyCommandRunning: /\[network\] Running \/(\S+) for (\S+) console/,
  statWipeExecuted: /Executed (\d+) stat-wipe command\(s\) for (\w+) \(punishment: ([\w-]+)\)/,
  statWipeRelog: /Your statistics are being reset pursuant to a recently expired ban/,
  networkUnknownServerName: /\[network\] .+ is not a server name in the proxy config/,
  networkRefusedStaffAction: /\[network\] Refused a staff action from /,
  backendListening: /modl \S+ listening on (\S+)/,
  remoteUnavailable: /Failed to connect to the modl server at/,
  remoteKeyRejected: /The modl server at \S+ rejected the API key/,
  memberKeyRejected:
    /The modl server at \S+ rejected the API key, retrying in \d+s\. Check remote\.api-key in \S+; create a server key in Panel > Settings > General > Minecraft servers > Add server\./,
  memberPlayersCanJoin: /Players can still join; the proxy enforces bans and mutes\. Staff tools on this server stay off until it connects\./,
  memberNoHub: /\[network\] The modl server at \S+ does not run a network hub; staff tools on this server stay off until it is updated\./,
  memberRealtimeDisabled:
    /\[network\] Realtime is disabled on the modl server at \S+ \(modl\.realtime\.ws\.enabled=false\); staff tools on this server stay off\./,
  memberDegraded: /\[network\] Staff tools on this server stay off: /,
  memberNetworkConnected: /\[network\] Connected to the modl network at \S+(?: as \S+)?\./,
  memberNetworkLost: /\[network\] Lost the connection to the modl network; keeping the current staff state and reconnecting\./,
  memberWebsocketBlocked:
    /\[network\] The modl server at \S+ accepted the key but its websocket could not be opened \(.+\)\. If a reverse proxy sits in front of the panel, allow websocket upgrades \(see https:\/\/github\.com\/modl-gg\/modl\/wiki\/HTTPS-and-Reverse-Proxy\)\./,
  memberKeyRevoked: /\[network\] The modl server rejected the server key; retrying\. Check remote\.api-key in \S+\./,
  memberSessionReplaced:
    /\[network\] Another server connected to the modl network(?: as \S+)? with this server key, so this server was disconnected; reconnecting in \d+ seconds\. Give every server its own key \(Panel > Settings > General > Minecraft servers > Add server\)\./,
  keylessLookupLimitReached:
    /proxycheck\.io without a key reached its limit of \d+ lookups today for this server's address\. New player addresses stay unknown, so they cannot carry alt bans, until tomorrow \(UTC\)\. A free proxycheck\.io key raises the limit: Panel > Settings > Moderation > Player addresses\./,
  loginInvalidName:
    /Refused the login of [0-9a-f-]{36}: its player name does not follow the Minecraft name rule /,
  loginCheckOverridden:
    /\S+ was refused before modl checked their bans, but another plugin allowed the login; modl denies it because their bans were not checked\./,
  playerAddressesNotForwarded:
    /Players are connecting from [a-z ]+ addresses, so their real IPs are not being forwarded\. modl will not link accounts by these addresses\./,
  plainHttpNotice:
    /remote\.url \S+ sends the API key without encryption to a public address\. Use https:\/\/ \(see https:\/\/github\.com\/modl-gg\/modl\/wiki\/HTTPS-and-Reverse-Proxy\)\./,
  databaseLockWait: /Another modl server is using this database; waiting up to \d+ seconds for it to stop\./,
  databaseLockLost: /modl lost its database lock and stopped serving to protect your data\. Restart the server\./,
  databaseRetainedLockLost:
    /The stopped modl server still has open database connections and lost its database lock\. Restart the server before another modl server uses this database\./,
  databaseDiskFull:
    /The disk that holds the modl database is full, so modl cannot save changes\. Free some disk space; modl saves again once there is room\./,
  databaseDurability:
    /The database is in \S+ journal mode instead of WAL; restart modl to repair it\.|The database is not syncing every commit to disk \(synchronous=\w+\)\.|The database session is not in strict SQL mode, so it could silently shorten or change values\.|The database setting \w+ is \S+; set it to \w+ so a crash cannot lose acknowledged moderation actions\./,
  restoreUploadsPending:
    /A restore of .+ stopped before it finished replacing the uploaded files, so the database and the uploaded files may not match\. Stop modl and run the same restore again, or delete .+ to keep both as they are\./,
  databaseNotIncremental:
    /The database file does not return freed space to the disk\. Restore it from a nightly backup with `restore --input` to convert it\./,
  sqliteNativeAccessWarning:
    /WARNING: (?:A restricted method in java\.lang\.System has been called|java\.lang\.System::load has been called by org\.sqlite\.SQLiteJDBCLoader in an unnamed module.*|Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module|Restricted methods will be blocked in a future release unless native access is enabled)/,
  importUnreadablePlayer: new RegExp(`${IMPORT_SOURCE} import could not read player \\S+: `),
  replayLink: /\/replay\?id=([A-Za-z0-9_-]+)/,
} as const;

export const HostedImportEndpoints = {
  state: "/v1/panel/setup/import",
  upload: "/v1/panel/setup/import/upload",
  uploadChunk: (importId: string, offset: number) =>
    `/v1/panel/setup/import/upload?importId=${encodeURIComponent(importId)}&offset=${offset}`,
  uploadComplete: "/v1/panel/setup/import/upload/complete",
  download: "/v1/panel/setup/import/download",
  preview: "/v1/panel/setup/import/preview",
  staff: "/v1/panel/setup/import/staff",
  start: "/v1/panel/setup/import/start",
  report: "/v1/panel/setup/import/report",
} as const;

export const HostedImportStatuses = {
  none: "HOSTED_IMPORT_STATUS_NONE",
  receiving: "HOSTED_IMPORT_STATUS_RECEIVING",
  verifying: "HOSTED_IMPORT_STATUS_VERIFYING",
  review: "HOSTED_IMPORT_STATUS_REVIEW",
  applying: "HOSTED_IMPORT_STATUS_APPLYING",
  paused: "HOSTED_IMPORT_STATUS_PAUSED",
  completed: "HOSTED_IMPORT_STATUS_COMPLETED",
  failed: "HOSTED_IMPORT_STATUS_FAILED",
} as const;

export const HostedImportIssueCodes = {
  fileSkippedByExport: "HOSTED_IMPORT_ISSUE_CODE_FILE_SKIPPED_BY_EXPORT",
  fileCaseConflict: "HOSTED_IMPORT_ISSUE_CODE_FILE_CASE_CONFLICT",
  webhookDisabled: "HOSTED_IMPORT_ISSUE_CODE_WEBHOOK_DISABLED",
  aiDisabled: "HOSTED_IMPORT_ISSUE_CODE_AI_DISABLED",
  commandRedacted: "HOSTED_IMPORT_ISSUE_CODE_COMMAND_REDACTED",
  punishmentWithoutData: "HOSTED_IMPORT_ISSUE_CODE_PUNISHMENT_WITHOUT_DATA",
  punishmentRollbackEnds: "HOSTED_IMPORT_ISSUE_CODE_PUNISHMENT_ROLLBACK_ENDS",
  roleRenamed: "HOSTED_IMPORT_ISSUE_CODE_ROLE_RENAMED",
  settingUnreadable: "HOSTED_IMPORT_ISSUE_CODE_SETTING_UNREADABLE",
} as const;

export const HostedImportPhases = {
  players: "HOSTED_IMPORT_PHASE_PLAYERS",
} as const;

export const HostedImportRetentions = {
  keepHistory: "HOSTED_IMPORT_RETENTION_KEEP_HISTORY",
} as const;

export const HostedImportErrors = {
  linkNotAllowed: "IMPORT_LINK_NOT_ALLOWED",
  uploadOffset: "IMPORT_UPLOAD_OFFSET",
  active: "IMPORT_ACTIVE",
  alreadyImported: "ALREADY_IMPORTED",
} as const;

export const ImportMessages = {
  localConsoleOnly: /Run this command in this server's own console\./,
  liteBansMissing: /LiteBans is not running on this server\. Install and enable it here/,
  sourceMissing: new RegExp(`No ${IMPORT_SOURCE} data was found on this server\\. Run this on the server that has it`),
  sourceLocked: new RegExp(`${IMPORT_SOURCE} is running and keeps its database file open\\. Remove its jar`),
  previewReady: /To start, run within 60 seconds: \/modl import [a-z]+ confirm/,
  previewCounts: new RegExp(`${IMPORT_SOURCE} has (\\d+) bans, (\\d+) mutes, (\\d+) warnings, (\\d+) kicks, and (\\d+) notes for (\\d+) players\\.`),
  detected: /Found here: [a-z, ]+\. Supported: [a-z, ]+\./,
  busyHere: /An import check or import is already running here\./,
  choicesRequired: /Both choices are required: \/modl import [a-z]+ confirm/,
  started: new RegExp(`${IMPORT_SOURCE} import started \\((?:widen|skip), (?:keep|standard)\\)\\.`),
  processingStarted: /modl received the export and is importing it\./,
  completed: new RegExp(`${IMPORT_SOURCE} import completed(?: with problems)?: \\d+ players imported`),
  statusDetails: new RegExp(`${IMPORT_SOURCE} import started from .+ \\(.+\\) with (?:widen|skip) and (?:keep|standard)\\.`),
  sourceReconnectFailed: new RegExp(`The connection to ${IMPORT_SOURCE} kept from the preview was lost and could not be opened again: `),
} as const;

export const HostedImportMessages = {
  started: /Importing data from modl\.gg\. Bans and mutes take effect as they are imported\./,
  resumed: /Resuming the import from modl\.gg after a restart\./,
  paused: /The import from modl\.gg paused: .+\. Open \S+ to resume it\./,
  finished: /The import from modl\.gg finished: (\d+) players, (\d+) punishments, (\d+) tickets, and (\d+) files\. See the report at \S+\./,
} as const;

export const NetworkLimits = { proxyCommandWindowMs: 1000 } as const;

export const ProxyCommandMessages = {
  disabled: /\/proxycmd is disabled for this server's key\. The owner can allow it in Panel > Settings > General > Minecraft servers; until then, run the command in the proxy console\./,
  disabledNotice: /\[network\] \/proxycmd is disabled for this server's key; enable it in Panel > Settings > General > Minecraft servers\./,
  sent: /Sent to the proxy: (.+)/,
} as const;

export const ServerKeyErrors = {
  scope: "SERVER_KEY_SCOPE",
} as const;

export const UpdateMessages = {
  available: /modl \S+ is available \(this server runs \S+\)\. /,
  backingUp: /Backing up the database before updating modl from \S+ to \S+/,
  backedUp: /Backed up the database to \S+/,
  withoutBackup: /Updating modl from \S+ to \S+ without a pre-update backup/,
  restoring: /Restoring the database from \S+, the backup made before updating modl from \S+ to \S+/,
  rolledBack: /The update from modl \S+ to \S+ did not finish, so modl returned to \S+/,
  updated: /Updated modl from \S+ to \S+/,
  didNotStart: /modl \S+ did not start(?::| within \d+ minutes| \()/,
  backupFailed: /The backup before updating modl from \S+ to \S+ failed: .+Nothing was changed\./,
  cannotStart: /modl cannot start: /,
  downgradeRefused: /This database was last used by modl \S+, which is newer than this modl \S+/,
  refusedServer: /Refused a \S+ server running modl \S+: this modl server is \S+/,
  prepared: /Prepared modl \S+\. The built-in panel restarts to finish the update\./,
  restarting: /Restarting the built-in panel to finish the update\.\.\./,
  restarted: /The built-in panel restarted\. Players can join again\./,
  returning: /modl \S+ did not start \(.+\)\. Returning to modl \S+\./,
  stagedUnusable: /The staged update to modl \S+ cannot be used \(.+\)\. Staying on modl \S+\./,
  restartRefused: /The built-in panel asked to restart for an update, but no staged update was found\.|The staged update could not be read: /,
  restartRemedy: /Restart the Minecraft server to finish or roll back the update\./,
} as const;

export const DatabaseMoveMessages = {
  restartingPanel: /Restarting the built-in panel to finish moving the database\.\.\./,
  restartRemedy: /Restart the Minecraft server to finish moving the database\./,
  restartsOnto: /modl copied its data to .+ and restarts on it now\./,
  exits: /modl copied its data to .+ and exits now; its container restart policy starts it again on the new database\./,
  waitsForRestart: /modl copied its data to .+\. Restart modl to finish the move; changes are paused until then\./,
  adopted: /modl now uses .+\. The previous database was kept unchanged\./,
  fellBack: /modl could not reach .+ \(.*\), so it keeps using .+\. Fix the connection, then move again in Panel > Settings > System > Database\./,
  waitingForTarget: /Waiting up to \d+ seconds for .+ to answer\./,
  stopped: /Moving the database to .+ stopped: .+\. modl keeps using .+\. Open Panel > Settings > System > Database to try again\./,
  panelChoiceRetired:
    /The database is set by MODL_DATABASE_URL or connection\.yml, so modl no longer uses the one chosen in the panel\. Its choice was kept as .+\./,
  importRefused: /modl is moving to another database\. Try again when the move finishes\./,
  copyMissing: /.+ does not hold the copy modl made \(.*\), so modl keeps using .+\. Move again in Panel > Settings > System > Database\./,
  reopened: /modl reopened this database, which was moved to .+ at \S+\. Remove MODL_DATABASE_REOPEN again\./,
} as const;

export const DatabaseMoveEndpoints = {
  settings: "/v1/panel/settings/database",
  test: "/v1/panel/settings/database/test",
  move: "/v1/panel/settings/database/move",
} as const;

export const DatabaseMoveEnums = {
  postgresql: "DATABASE_KIND_POSTGRESQL",
  sqlite: "DATABASE_KIND_SQLITE",
  restartInPlace: "DATABASE_RESTART_MODE_IN_PLACE",
} as const;

export const CompatibilityMessages = {
  mismatch: /This plugin is modl \S+, but the modl server at \S+ runs .+\./,
  pluginOlder: /Update the modl plugin to \S+\.x on this server and restart it\./,
  serverOlder: /Update the modl server to \S+\.x\. modl reconnects on its own afterwards\./,
  unknown: /Run the same MAJOR\.MINOR version of modl on both sides\./,
  acceptedAgain: /The modl server accepts this plugin again\./,
  playerRefusal: /being updated/i,
} as const;

export const UpdateErrors = {
  checkDisabled: "UPDATE_CHECK_DISABLED",
  notInstallable: "UPDATE_NOT_INSTALLABLE",
} as const;

export const UpdateEnums = {
  embedded: "DEPLOYMENT_KIND_EMBEDDED",
  jar: "DEPLOYMENT_KIND_JAR",
  docker: "DEPLOYMENT_KIND_DOCKER",
  available: "INSTALL_AVAILABILITY_AVAILABLE",
  dockerInstall: "INSTALL_AVAILABILITY_DOCKER",
  pluginJarRequired: "INSTALL_AVAILABILITY_PLUGIN_JAR_REQUIRED",
  installFailed: "INSTALL_PHASE_FAILED",
  backupDirectory: "INSTALL_FAILURE_BACKUP_DIRECTORY",
} as const;

export const PANEL_STALL_TIMEOUT_MS = 180_000;

export const EMBEDDED_STARTUP_PROGRESS = new RegExp(
  [ConsolePatterns.panelArchiveDownload, ConsolePatterns.serverLibrariesDownload, ConsolePatterns.serverLibrariesInstalled, ConsolePatterns.panelStarted]
    .map((pattern) => pattern.source)
    .join("|"),
);

export const StartupFailurePatterns = {
  separator: /\[modl\]:? ={20,}\s*$/m,
  reason: /modl is not running: /,
  remedy: /(?:Edit \S+|Fix the problem|Fix the modl server address|Check the errors above|Install PacketEvents \S+ or newer) and restart the (?:server|proxy)\b/,
  coreFailure: /\[modl\]:? modl failed to (?:start|load)(?: in behind-proxy mode)?\s*$/m,
  consequence: /Players cannot join until this is fixed\./,
} as const;

export const CONFIG_REJECTED = /\[modl\]:? connection\.yml: /;

const READY_PATTERNS: Record<MinecraftKind, RegExp> = {
  paper: ConsolePatterns.paperReady,
  velocity: ConsolePatterns.velocityReady,
  bungeecord: ConsolePatterns.bungeecordReady,
};

const STOP_COMMANDS: Record<MinecraftKind, string> = { paper: "stop", velocity: "end", bungeecord: "end" };

export function readyPattern(kind: MinecraftKind): RegExp {
  return READY_PATTERNS[kind];
}

export function stopCommand(kind: MinecraftKind): string {
  return STOP_COMMANDS[kind];
}

export function punishmentIssued(type: string, target: string): RegExp {
  return new RegExp(`(?:Successfully )?issued ${escapeRegExp(type)} for ${escapeRegExp(target)}`, "i");
}

export function replayCaptured(player: string): RegExp {
  return new RegExp(`Replay (?:successfully )?captured for ${escapeRegExp(player)}`, "i");
}

export const PACKETEVENTS_REQUIRED_REFUSAL = /PacketEvents \d+\.\d+\.\d+ or newer/;

export const HotbarItems = {
  vanish: /^Vanish: (?:ON|OFF)$/,
  freezeTarget: /^Freeze Target$/,
} as const;

export const StaffMessages = {
  frozen: /You have been frozen/i,
  unfrozen: /You have been unfrozen/i,
  staffModeEnabled: /Staff mode enabled/i,
  staffModeDisabled: /Staff mode disabled/i,
  targeting: /Now targeting|Connecting to/i,
  vanishToggled: /You are now vanished|You are no longer vanished/i,
  staffListVanishTag: /\[V\]/,
} as const;

export function frozeBroadcast(target: string): RegExp {
  return new RegExp(`: froze ${escapeRegExp(target)}\\]`);
}

export const PARDON_SUCCESS = /(?:Successfully )?pardoned (?:punishment #|\S+'s \d+ )/i;

export const REPORT_TICKET_ID = /Ticket ID:\s*([A-Za-z0-9_-]+)/;

export function reportAlert(reporter: string, reported: string): RegExp {
  return new RegExp(`${escapeRegExp(reporter)}: reported ${escapeRegExp(reported)}\\b.*\\((?:Chat|Gameplay)\\)`);
}

export const ReplayRecordingMessages = {
  started: (player: string) => new RegExp(`\\bRecording ${escapeRegExp(player)}\\b`),
  stopped: (player: string) => new RegExp(`Stopped recording ${escapeRegExp(player)}\\b`),
  recording: (player: string) => new RegExp(`${escapeRegExp(player)} is being recorded \\(([^)]+)\\) for (\\d[\\dydhms ]*)`),
  notRecording: (player: string) => new RegExp(`${escapeRegExp(player)} is not being recorded\\b`),
  memory: /Replay memory: \d+\.\d of \d+\.\d MB, recordings: \d+/,
  answer: /\bRecording \S+|Stopped recording \S+| is being recorded \(| is not being recorded|Could not change the recording of |is not connected to modl\.|is already being recorded| is kept \(| left the server|Could not read the recording status of |Replay recording is not available|Player not found\./,
  staffOrigin: "/replay record",
} as const;

export const REPLAY_CAPTURE_FAILED =
  /Failed to capture replay|No active recording|No server is connected to capture a replay|Replay recording is not available/i;

export const LOGIN_CODE_LINE = /Login code for (\S+): (\d{6})\b/;

export function loginCodeIn(line: string, email: string): string | null {
  const match = LOGIN_CODE_LINE.exec(line);
  return match?.[1]?.toLowerCase() === email.toLowerCase() && match[2] !== undefined ? match[2] : null;
}

export function findLoginCode(lines: readonly string[], email: string): string | null {
  for (const line of [...lines].reverse()) {
    const code = loginCodeIn(line, email);
    if (code !== null) return code;
  }
  return null;
}

export const PlayerNotices = {
  muted: /You (?:are|have been) (?:temporarily |permanently )?muted/i,
} as const;

export const PlayerCommands = {
  link: (code: string) => `/modl link ${code}`,
  panelLogin: "/modl panel",
  standing: "/standing",
  report: (target: string) => `/report ${target}`,
  punish: (target: string) => `/punish ${target}`,
  staffMode: "/staffmode",
  staffMenu: "/staffmenu",
  target: (player: string) => `/target ${player}`,
  freeze: (player: string) => `/freeze ${player}`,
  switchServer: (server: string) => `/server ${server}`,
} as const;

export const PaperProblems = {
  reloadCommandTreeRace: /Paper Async Command Builder Thread Pool[\s\S]*ConcurrentModificationException[\s\S]*net\.minecraft\.commands\.Commands\.sendAsync/,
} as const;

export const ConsoleCommands = {
  ban: (player: string, reason: string) => `ban ${player} ${reason}`,
  statWipeBan: (player: string, duration: string, reason: string) => `ban ${player} ${duration} -stat-wipe ${reason}`,
  mute: (player: string, reason: string) => `mute ${player} ${reason}`,
  unmute: (player: string) => `unmute ${player}`,
  alts: (player: string, page: number) => (page <= 1 ? `alts ${player} -p` : `alts ${player} -p ${page}`),
  replayCapture: (player: string) => `replay capture ${player}`,
  replayRecord: (player: string) => `replay record ${player}`,
  replayStop: (player: string) => `replay stop ${player}`,
  replayStatus: (player: string) => `replay status ${player}`,
  freeze: (player: string) => `freeze ${player}`,
  staffList: "stafflist",
  importPreview: "modl import litebans",
  importConfirm: (choices: string) => `modl import litebans confirm ${choices}`.trimEnd(),
  importSources: "modl import",
  importSourcePreview: (source: string) => `modl import ${source}`,
  importSourceConfirm: (source: string, choices: string) => `modl import ${source} confirm ${choices}`,
  importStatus: "modl import status",
  importCancel: "modl import cancel",
  proxyImport: "proxycmd modl import litebans",
  proxyModlVersion: "proxycmd modl",
  reload: "bukkit:reload confirm",
  modlVersion: "modl",
  velocityPlayerList: "glist all",
} as const;

export const Endpoints = {
  health: "/actuator/health",
  setupStatus: "/v1/public/setup/status",
  setupClaim: "/v1/public/setup/claim",
  setupSignIn: "/v1/public/setup/sign-in",
  setupState: "/v1/panel/setup",
  setupStep: (step: string) => `/v1/panel/setup/steps/${encodeURIComponent(step)}`,
  setupPublicUrlCheck: "/v1/panel/setup/public-url/check",
  connectedServers: "/v1/panel/servers/connected",
  updates: "/v1/panel/updates",
  updatesCheck: "/v1/panel/updates/check",
  updatesDismiss: "/v1/panel/updates/dismiss",
  updatesInstall: "/v1/panel/updates/install",
  serverKeys: "/v1/panel/settings/server-keys",
  serverKey: (id: string) => `/v1/panel/settings/server-keys/${encodeURIComponent(id)}`,
  minecraftStartup: "/v2/minecraft/startup",
  minecraftSyncV3: "/v3/minecraft/players/sync",
  generalSettings: "/v1/panel/settings/general",
  playerAddressSettings: "/v1/panel/settings/player-addresses",
  webhookSettings: "/v1/panel/settings/webhooks",
  aiModerationSettings: "/v1/panel/settings/ai-moderation",
  dataRetentionSettings: "/v1/panel/settings/data-retention",
  panelStaff: "/v1/panel/staff",
  panelPlayer: (uuid: string) => `/v1/panel/players/${encodeURIComponent(uuid)}`,
  minecraftCommandLogs: (uuid: string) => `/v1/minecraft/players/${encodeURIComponent(uuid)}/command-logs`,
  minecraftLinkedAccounts: (uuid: string) => `/v1/minecraft/players/${encodeURIComponent(uuid)}/linked-accounts`,
  mailSettings: "/v1/panel/settings/mail",
  mailTest: "/v1/panel/settings/mail/test",
  staffInvite: "/v1/panel/staff/invite",
  sendEmailCode: "/v1/panel/auth/send-email-code",
  verifyEmailCode: "/v1/panel/auth/verify-email-code",
  profile: "/v1/panel/auth/me",
  consumeLoginLink: "/v1/panel/auth/link",
  minecraftLink: "/v1/panel/account/minecraft-link",
  revealApiKey: "/v1/panel/settings/api-keys/panel/reveal",
  ticket: (id: string) => `/v1/panel/tickets/${encodeURIComponent(id)}`,
  importLatest: "/v1/minecraft/migration/jobs/latest",
  importFile: (id: string) => `/v1/minecraft/migration/jobs/${encodeURIComponent(id)}/file`,
  minecraftPlayer: (uuid: string) => `/v1/minecraft/players/${encodeURIComponent(uuid)}?punishmentLimit=50`,
  panelReplay: (id: string) => `/v1/panel/replays/${encodeURIComponent(id)}`,
  panelReplayFile: (id: string) => `/v1/panel/replays/${encodeURIComponent(id)}/file`,
  replayShares: (id: string) => `/v1/panel/replays/${encodeURIComponent(id)}/shares`,
  replayShare: (id: string, shareId: string) => `/v1/panel/replays/${encodeURIComponent(id)}/shares/${encodeURIComponent(shareId)}`,
  sharedReplay: (token: string) => `/v1/public/replays/shared/${encodeURIComponent(token)}`,
  sharedReplayFile: (token: string) => `/v1/public/replays/shared/${encodeURIComponent(token)}/file`,
  replaySettings: "/v1/panel/settings/replays",
  storageObject: (key: string) => `/v1/storage/objects/${key}`,
} as const;

export const LOGIN_LINK_URL = /https?:\/\/[^\s"'\\]+\/auth\/link\?token=([A-Za-z0-9._~-]+)/;

export const LOGIN_LINK_TEXT = /^Open the panel$/;

export const ACCOUNT_LINKED = /^Your Minecraft account is now linked to your panel account\.$/;

export const SETUP_PAGE = "/setup";

export type SetupCodeKind = "setup" | "owner";

const OWNER_SIGN_IN_CODE = /owner sign-in code: /i;

export function setupCodeKind(text: string): SetupCodeKind {
  return OWNER_SIGN_IN_CODE.test(text) ? "owner" : "setup";
}

export const SetupCodeFiles = {
  plugin: "plugins/modl/setup-code.txt",
  standalone: "setup-code.txt",
} as const;

export const DataDirectories = {
  plugin: "plugins/modl/data",
  standalone: ".",
} as const;

export const SETUP_CODE_FILE_CODE = /code: ([A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4})/;
export const SETUP_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
export const SETUP_CODE_ATTEMPTS_PER_IP = 5;

export const SetupPhases = {
  unclaimed: "SETUP_PHASE_UNCLAIMED",
  ownerSignIn: "SETUP_PHASE_OWNER_SIGN_IN",
  ready: "SETUP_PHASE_READY",
} as const;

export type SetupPhase = (typeof SetupPhases)[keyof typeof SetupPhases];

export function isSetupPhase(value: string): value is SetupPhase {
  return (Object.values(SetupPhases) as string[]).includes(value);
}

export const SetupHosts = { plugin: "SETUP_HOST_PLUGIN", standalone: "SETUP_HOST_STANDALONE" } as const;

export const SettingsErrors = { serverNameManaged: "SERVER_NAME_MANAGED_BY_CONFIG" } as const;

export const AuthErrors = { unauthenticated: "UNAUTHENTICATED" } as const;

export const SetupErrors = {
  codeInvalid: "SETUP_CODE_INVALID",
  codeLocked: "SETUP_CODE_LOCKED",
  codeExpired: "SETUP_CODE_EXPIRED",
  alreadyClaimed: "SETUP_ALREADY_CLAIMED",
  signInNotAvailable: "SETUP_SIGN_IN_NOT_AVAILABLE",
  stepNotSkippable: "SETUP_STEP_NOT_SKIPPABLE",
  stepOutOfOrder: "SETUP_STEP_OUT_OF_ORDER",
} as const;

export interface ReplaySettings {
  enabled: boolean;
  recordEveryPlayer: boolean;
  recordOnAnticheatFlag: boolean;
  anticheatIntegration: boolean;
  windowSeconds: number;
  radiusBlocks: number;
  memoryBudgetMb: number;
  anticheatName: string;
}

export const RECORD_EVERY_PLAYER: ReplaySettings = {
  enabled: true,
  recordEveryPlayer: true,
  recordOnAnticheatFlag: true,
  anticheatIntegration: false,
  windowSeconds: 60,
  radiusBlocks: 64,
  memoryBudgetMb: 64,
  anticheatName: "Anti-cheat",
};

export const SETUP_REPLAY_CHOICE: Partial<ReplaySettings> = { enabled: false, anticheatIntegration: false };

export const ReplayErrors = {
  shareNotFound: "REPLAY_SHARE_NOT_FOUND",
  notChosen: "SETUP_REPLAYS_NOT_CHOSEN",
} as const;

export const ReplayShareExpiries = { sevenDays: "REPLAY_SHARE_EXPIRY_SEVEN_DAYS" } as const;

export const REPLAY_FILE_MAGIC = "MODL";

export const SETUP_STEPS = [
  "account",
  "import",
  "identity",
  "address",
  "email",
  "servers",
  "minecraft-account",
  "staff",
  "player-addresses",
  "ai",
  "replays",
  "content",
] as const;

export type SetupStep = (typeof SETUP_STEPS)[number];

export function setupStepEnum(step: SetupStep): string {
  return `SETUP_STEP_${step.toUpperCase().replace(/-/g, "_")}`;
}

export const SetupStepStatuses = {
  pending: "SETUP_STEP_STATUS_PENDING",
  done: "SETUP_STEP_STATUS_DONE",
  skipped: "SETUP_STEP_STATUS_SKIPPED",
} as const;

export const PublicUrlVerdicts = {
  reachable: "PUBLIC_URL_VERDICT_REACHABLE",
  unreachable: "PUBLIC_URL_VERDICT_UNREACHABLE",
  otherService: "PUBLIC_URL_VERDICT_OTHER_SERVICE",
  invalid: "PUBLIC_URL_VERDICT_INVALID",
} as const;

export const MailTestOutcomes = { connectFailed: "MAIL_TEST_OUTCOME_CONNECT_FAILED" } as const;

const CONNECTED_PLATFORMS: Record<MinecraftKind, string> = { paper: "SPIGOT", velocity: "VELOCITY", bungeecord: "BUNGEECORD" };

export function connectedPlatform(kind: MinecraftKind): string {
  return CONNECTED_PLATFORMS[kind];
}
