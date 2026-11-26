import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { dateValue, everyTypeValue, javaLegacyUuid, longValue, objectIdValue } from "./extended-json";
import { ARCHIVE_COLLECTIONS, buildHostedArchive, type ArchiveCollection, type ArchiveObject, type HostedArchiveContent } from "./hosted-archive";
import { HARNESS_ROOT } from "./paths";
import { offlineUuid } from "./util";

const RAW_BSON_LINE_FILE = join(HARNESS_ROOT, "fixtures", "hosted-import", "form-b-audit.ndjson");
const DAY_MS = 86_400_000;
const FILLER_PLAYERS = 120_000;
const DELETED_TYPE_ORDINAL = 42;
const CHAT_LINES = 60;
const OLD_LOG_AGE_DAYS = 400;
const KEY_PREFIX = "server_harness/";
const BASE_URL = "https://cdn.modl.gg/";
const OTHER_HOST = "https://old-bucket.example.net/";
const SECRET_IN_COMMAND = "harness-secret-password";
const HOSTILE_MAIL_HOST = "mail.attacker.example";
const DISCORD_WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/harness-fixture-token";

export interface ImportedPlayer {
  name: string;
  uuid: string;
}

export interface HostedImportSeed {
  banned: ImportedPlayer;
  rolledBack: ImportedPlayer;
  linked: ImportedPlayer;
  deletedType: ImportedPlayer;
  selectedStaff: { sourceId: string; email: string };
  unselectedStaffEmail: string;
  archivedOwnerEmail: string;
  deletedTypeName: string;
  auditDescription: string;
  secretInCommand: string;
  hostileMailHost: string;
  oldLogAgeDays: number;
  evidence: readonly Buffer[];
  expected: { players: number; punishments: number; tickets: number; files: number };
}

export interface HostedImportFixture {
  seed: HostedImportSeed;
  archive: Buffer;
  truncated: Buffer;
  wrongHash: Buffer;
  foreignKey: Buffer;
}

type NotableCase = Record<string, unknown> & { punishments: Record<string, unknown>[] };

type FixtureCast = Omit<HostedImportSeed, "evidence" | "expected">;

interface FixtureTimes {
  now: Date;
  recent: Date;
  old: Date;
}

function fixtureTimes(): FixtureTimes {
  const now = new Date(Date.now() - 60_000);
  return { now, recent: new Date(now.getTime() - 2 * DAY_MS), old: new Date(now.getTime() - OLD_LOG_AGE_DAYS * DAY_MS) };
}

function player(name: string): ImportedPlayer {
  return { name, uuid: offlineUuid(name) };
}

function storedObject(relativeKey: string, content: string, contentType: string): ArchiveObject {
  return { key: `${KEY_PREFIX}${relativeKey}`, content: Buffer.from(content, "utf8"), contentType };
}

function fixtureObjects(): { upper: ArchiveObject; lower: ArchiveObject; attachment: ArchiveObject; replay: ArchiveObject } {
  return {
    upper: storedObject("evidence/Proof.png", "harness evidence, upper case key", "image/png"),
    lower: storedObject("evidence/proof.png", "harness evidence, lower case key", "image/png"),
    attachment: storedObject("tickets/BUG-100001/attachment.png", "harness attachment", "image/png"),
    replay: storedObject("replays/harness-replay.modlreplay", "MODL harness replay", "application/octet-stream"),
  };
}

type FixtureObjects = ReturnType<typeof fixtureObjects>;

function issued(id: string, typeOrdinal: number, at: Date, data: Record<string, unknown>): Record<string, unknown> {
  return { id, typeOrdinal, issuerName: "Hosted staff", issued: dateValue(at), started: dateValue(at), data };
}

function notableCases(seed: FixtureCast, times: FixtureTimes, objects: FixtureObjects): NotableCase[] {
  const { banned, rolledBack, linked, deletedType } = seed;
  const { recent, old } = times;
  const profile = (source: ImportedPlayer) => ({
    _id: `player-${source.name}`,
    usernames: [{ username: source.name, date: dateValue(old) }],
  });
  return [
    {
      ...profile(banned),
      minecraftUuid: banned.uuid,
      notes: [{ id: "player-note", text: "Known alt", date: dateValue(recent) }],
      ipAddresses: [{ ipAddress: "198.51.100.23", firstLogin: dateValue(old), logins: [dateValue(old)] }],
      data: {
        firstJoin: dateValue(old),
        linkedAccounts: [linked.uuid],
        pendingNotifications: [{ id: "notification", message: "Welcome back", timestamp: longValue(recent.getTime()) }],
      },
      punishments: [
        {
          ...issued("harness-ban", 2, recent, { reason: "hosted import ban", enforcementCategory: "BAN" }),
          issuerId: seed.selectedStaff.sourceId,
          attachedTicketIds: ["BUG-100001"],
          notes: [{ _id: "ban-note", text: "Seen in the replay", date: dateValue(recent) }],
          evidence: [
            { url: `${BASE_URL}${objects.upper.key}`, type: "link", uploadedAt: dateValue(recent) },
            { url: `${OTHER_HOST}${objects.lower.key}`, type: "link", uploadedAt: dateValue(recent) },
          ],
        },
        issued("shared-id", 0, old, { reason: "first kick with a shared id" }),
      ],
    },
    {
      ...profile(rolledBack),
      minecraftUuid: javaLegacyUuid(rolledBack.uuid),
      punishments: [
        {
          ...issued("rolled-back-ban", 2, recent, { reason: "rolled back", enforcementCategory: "BAN" }),
          modifications: [{ id: "rollback", type: "ROLLBACK", date: dateValue(recent), issuerName: "Hosted staff" }],
        },
      ],
    },
    {
      ...profile(linked),
      minecraftUuid: linked.uuid,
      punishments: [
        issued("shared-id", 0, old, { reason: "second kick with a shared id" }),
        { _id: "without-data", typeOrdinal: 2, issuerName: "Hosted staff", issued: dateValue(old), started: dateValue(old) },
      ],
    },
    {
      ...profile(deletedType),
      minecraftUuid: deletedType.uuid,
      punishments: [
        issued("deleted-type", DELETED_TYPE_ORDINAL, recent, {
          reason: "saturated duration",
          enforcementCategory: "MUTE",
          duration: 1e25,
        }),
      ],
    },
  ];
}

function fillerPlayers(times: FixtureTimes): Record<string, unknown>[] {
  return Array.from({ length: FILLER_PLAYERS }, (_, index) => {
    const name = `Fill${index}`;
    return { _id: `filler-${index}`, minecraftUuid: offlineUuid(name), usernames: [{ username: name, date: dateValue(times.old) }] };
  });
}

function configuration(seed: FixtureCast, times: FixtureTimes): Partial<Record<ArchiveCollection, unknown[]>> {
  const created = dateValue(times.old);
  return {
    staffroles: [
      { _id: "role-mods", name: "Imported mods", permissions: ["punishment.view"], createdAt: created },
      { _id: "role-fake-owner", name: "Super Admin", permissions: ["punishment.view"], createdAt: created },
    ],
    staffs: [
      {
        _id: seed.selectedStaff.sourceId,
        email: seed.selectedStaff.email,
        username: "Restored",
        role: "role-mods",
        createdAt: created,
        subscribedTickets: [{ ticketId: "BUG-100001", subscribedAt: dateValue(times.recent) }],
      },
      { _id: "staff-unselected", email: seed.unselectedStaffEmail, username: "Unselected", role: "role-fake-owner" },
    ],
    settings: [
      {
        _id: objectIdValue("665f1c2e8b3e4a0012340001"),
        type: "general",
        version: 1,
        data: { serverDisplayName: "Imported Network", defaultLanguage: "en" },
      },
      { _id: "settings-mail", type: "mail", data: { host: HOSTILE_MAIL_HOST, port: 25, password: "hostile", from: "owner@attacker.example" } },
      {
        _id: "settings-webhooks",
        type: "webhookSettings",
        data: { enabled: true, discordWebhookUrl: DISCORD_WEBHOOK, botName: "modl" },
      },
      {
        _id: "settings-ai",
        type: "aiModerationSettings",
        data: { enableAIReview: true, enableAutomatedActions: true, aiPunishmentConfigs: {} },
      },
    ],
    knowledgebasecategories: [{ _id: "category-rules", name: "Rules", slug: "rules", createdAt: created }],
    knowledgebasearticles: [
      { _id: "article-rules", title: "Chat rules", content: "Be kind", categoryId: "category-rules", createdAt: created },
    ],
    homepagecards: [
      { _id: "card-rules", title: "Rules", actionType: "category_dropdown", categoryId: "category-rules", createdAt: created },
    ],
  };
}

function records(seed: FixtureCast, times: FixtureTimes, objects: FixtureObjects): Partial<Record<ArchiveCollection, unknown[]>> {
  const { banned, linked } = seed;
  const { recent, old } = times;
  return {
    tickets: [
      {
        _id: "BUG-100001",
        type: "bug",
        status: "open",
        subject: "Imported bug",
        creatorUuid: linked.uuid,
        creatorName: linked.name,
        created: dateValue(recent),
        updatedAt: dateValue(recent),
        replayId: "harness-replay",
        formData: everyTypeValue(recent),
        replies: [
          { id: "reply-with-id", content: "Looking", created: dateValue(recent), attachments: [{ key: objects.attachment.key }] },
          { _id: "reply-with-underscore-id", content: "Found it", created: dateValue(recent) },
        ],
      },
    ],
    replays: [
      { _id: "harness-replay", status: "COMPLETE", targetUuid: banned.uuid, storageKey: objects.replay.key, createdAt: dateValue(recent) },
    ],
    storage_files: [{ _id: "file-proof", key: objects.upper.key, fileName: "Proof.png", createdAt: dateValue(recent) }],
    chat_logs: Array.from({ length: CHAT_LINES }, (_, index) => ({
      uuid: banned.uuid,
      username: banned.name,
      message: `imported message ${index}`,
      timestamp: longValue(old.getTime() + index),
    })),
    command_logs: [
      { uuid: banned.uuid, username: banned.name, command: `/login ${seed.secretInCommand}`, timestamp: longValue(recent.getTime()) },
    ],
    logs: [
      {
        created: dateValue(recent),
        level: "info",
        source: "hosted-fixture",
        description: seed.auditDescription,
        metadata: { playerId: `player-${banned.name}`, punishmentId: "harness-ban" },
      },
    ],
  };
}

function encodedLines(documents: Partial<Record<ArchiveCollection, unknown[]>>): Partial<Record<ArchiveCollection, string[]>> {
  return Object.fromEntries(ARCHIVE_COLLECTIONS.map((name) => [name, (documents[name] ?? []).map((document) => JSON.stringify(document))]));
}

function createCast(playerName: (prefix: string) => string): FixtureCast {
  const stamp = Date.now().toString(36);
  return {
    banned: player(playerName("HBan")),
    rolledBack: player(playerName("HRol")),
    linked: player(playerName("HLnk")),
    deletedType: player(playerName("HDel")),
    selectedStaff: { sourceId: "staff-selected", email: `restored-${stamp}@modl.test` },
    unselectedStaffEmail: `unselected-${stamp}@modl.test`,
    archivedOwnerEmail: `previous-owner-${stamp}@modl.test`,
    deletedTypeName: `Type ${DELETED_TYPE_ORDINAL}`,
    auditDescription: "Imported audit entry",
    secretInCommand: SECRET_IN_COMMAND,
    hostileMailHost: HOSTILE_MAIL_HOST,
    oldLogAgeDays: OLD_LOG_AGE_DAYS,
  };
}

function archiveContent(
  seed: FixtureCast,
  times: FixtureTimes,
  collections: HostedArchiveContent["collections"],
  objects: readonly ArchiveObject[],
): HostedArchiveContent {
  return {
    exportId: randomUUID(),
    createdAt: times.now,
    serverName: "Imported Network",
    subdomain: "harness",
    adminEmail: seed.archivedOwnerEmail,
    keyPrefix: KEY_PREFIX,
    publicBaseUrls: [BASE_URL],
    collections,
    objects,
    skippedObjects: [{ key: `${KEY_PREFIX}evidence/removed.png`, reason: "DELETED_DURING_EXPORT" }],
    excludedCollections: [{ name: "sessions", reason: "SESSION", approximateDocuments: 12 }],
  };
}

export function createHostedImportFixture(playerName: (prefix: string) => string): HostedImportFixture {
  const times = fixtureTimes();
  const objects = fixtureObjects();
  const storedObjects = Object.values(objects);
  const cast = createCast(playerName);
  const notable = notableCases(cast, times, objects);
  const seed: HostedImportSeed = {
    ...cast,
    evidence: [objects.upper.content, objects.lower.content],
    expected: {
      players: notable.length + FILLER_PLAYERS,
      punishments: notable.reduce((total, entry) => total + entry.punishments.length, 0),
      tickets: 1,
      files: storedObjects.length,
    },
  };
  const shared = encodedLines({ ...configuration(seed, times), ...records(seed, times, objects) });
  const notableLines = notable.map((entry) => JSON.stringify(entry));
  const rawBsonLines = readFileSync(RAW_BSON_LINE_FILE, "utf8").split("\n").filter((line) => line !== "");
  const withRawBson = { ...shared, logs: [...(shared.logs ?? []), ...rawBsonLines] };
  const small = { ...withRawBson, players: notableLines };
  const fillerLines = fillerPlayers(times).map((entry) => JSON.stringify(entry));
  const full = { ...withRawBson, players: [...notableLines, ...fillerLines] };

  const smallArchive = buildHostedArchive(archiveContent(seed, times, small, storedObjects));
  const foreign: ArchiveObject = { key: "../evil.sh", content: Buffer.from("#!/bin/sh\n"), contentType: "text/x-sh" };
  return {
    seed,
    archive: buildHostedArchive(archiveContent(seed, times, full, storedObjects)),
    truncated: smallArchive.subarray(0, Math.floor(smallArchive.length / 2)),
    wrongHash: buildHostedArchive(archiveContent(seed, times, small, storedObjects), { wrongHashOf: "players" }),
    foreignKey: buildHostedArchive(archiveContent(seed, times, small, [...storedObjects, foreign])),
  };
}
