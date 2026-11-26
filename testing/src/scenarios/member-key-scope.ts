import { ConsoleCommands, ConsolePatterns, Endpoints, NetworkLimits, ProxyCommandMessages, ServerKeyErrors } from "../lib/contract";
import { apiErrorCode, HttpStatus, type HttpResult } from "../lib/http";
import type { LogMark } from "../lib/logs";
import { createServerKey, listServerKeys, ProxiedServerStates, replaceServerKey, revokeServerKey, ServerKeyScopes, setProxyCommands } from "../lib/server-keys";
import { patchConnection } from "../lib/topologies/server-files";
import { defineScenario, type ScenarioContext } from "./framework";
import { awaitProxiedServer, memberNames } from "./support";

const SCOPE_CHECK_KEY = "scope-check";
const NOTICE_TIMEOUT_MS = 90_000;
const RECONFIGURE_TIMEOUT_MS = 420_000;

const PROTOBUF_CONTENT_TYPE = "application/x-protobuf";

async function withMemberKey(t: ScenarioContext, secret: string, path: string, json: unknown): Promise<HttpResult> {
  return t.instance.anonymousSession().request(path, { method: "POST", auth: "none", headers: { "X-API-Key": secret }, json });
}

async function keyIdOf(t: ScenarioContext, name: string): Promise<string> {
  const key = (await listServerKeys(t.instance)).find((entry) => entry.name === name);
  t.require(key, `no server key is named ${name}`);
  return key.id;
}

async function awaitLine(t: ScenarioContext, server: string, pattern: RegExp, since: LogMark): Promise<string> {
  const line = await t.env.log(server).waitFor(pattern, { since, timeoutMs: NOTICE_TIMEOUT_MS });
  t.evidence(`${server}: ${pattern.toString()}`, line.text);
  return line.text;
}

async function rejoinWithNewKey(t: ScenarioContext, member: string): Promise<void> {
  const key = await replaceServerKey(t.instance, member, ServerKeyScopes.behindProxy);
  const waiting = await awaitProxiedServer(t, member, ProxiedServerStates.notConnected);
  t.check(`the proxy server ${member} is listed with its new key before it connects`, waiting.keyId === key.id, waiting);
  patchConnection(t.env.server(member).dataDir, { "remote.api-key": key.secret });
  t.evidence("restart", await t.restart(member));
  const connected = await awaitProxiedServer(t, member, ProxiedServerStates.connected, RECONFIGURE_TIMEOUT_MS);
  t.check(`the proxy server ${member} is listed as connected with its new key`, connected.keyId === key.id, connected);
}

export const memberKeyScope = defineScenario({
  name: "member-key-scope",
  description: "a behind-proxy server key reaches only the member endpoints, cannot start a core, runs /proxycmd only after the owner allows it, and revoking it disconnects the member at once",
  topologies: ["network", "network-bungee"],
  async run(t: ScenarioContext) {
    const member = memberNames(t).at(-1);
    t.require(member, "the topology has no server behind the proxy");
    const proxy = t.coreServer;

    await t.step("a behind-proxy key is refused outside the member endpoints", async () => {
      const key = await createServerKey(t.instance, SCOPE_CHECK_KEY, ServerKeyScopes.behindProxy);
      try {
        const sync = await t.instance.anonymousSession().request(Endpoints.minecraftSyncV3, {
          method: "POST",
          auth: "none",
          headers: { "X-API-Key": key.secret, "Content-Type": PROTOBUF_CONTENT_TYPE, Accept: `application/json, ${PROTOBUF_CONTENT_TYPE};q=0.1` },
          octets: Buffer.alloc(0),
        });
        t.evidence("sync with a member key", { status: sync.status, contentType: sync.contentType, body: sync.json ?? sync.text });
        t.check("the sync endpoint answers 403", sync.status === HttpStatus.forbidden, sync.status);
        const protobufError = sync.contentType.includes(PROTOBUF_CONTENT_TYPE) && sync.text.includes(ServerKeyErrors.scope);
        t.check(`the refusal carries ${ServerKeyErrors.scope}`, apiErrorCode(sync) === ServerKeyErrors.scope || protobufError, sync.contentType);

        const asCore = await withMemberKey(t, key.secret, Endpoints.minecraftStartup, { serverRole: "CORE", platformType: "VELOCITY" });
        t.evidence("startup as a core with a member key", { status: asCore.status, body: asCore.json });
        t.check("a member key cannot start a core", asCore.status === HttpStatus.forbidden && apiErrorCode(asCore) === ServerKeyErrors.scope,
          asCore.json);

        const asMember = await withMemberKey(t, key.secret, Endpoints.minecraftStartup, { serverRole: "MEMBER", platformType: "SPIGOT" });
        t.check("the same key starts a member", asMember.status === HttpStatus.ok, { status: asMember.status, body: asMember.json });
      } finally {
        await revokeServerKey(t.instance, key.id);
      }
    });

    const keyId = await t.step(`${member}'s key is found`, async () => keyIdOf(t, member));

    try {
      await t.step(`/proxycmd from ${member} is refused while its key does not allow it`, async () => {
        const updated = await setProxyCommands(t.instance, keyId, false);
        t.require(updated.status === HttpStatus.ok, `turning /proxycmd off answered ${updated.status}`);
        const proxySince = await t.env.log(proxy).mark();
        await t.consoleExpect(ConsoleCommands.proxyModlVersion, ProxyCommandMessages.disabled, { server: member, timeoutMs: 15_000 });
        const ran = await t.env.log(proxy).find(ConsolePatterns.networkProxyCommandRunning, proxySince);
        t.check("the proxy ran nothing for the member", ran.length === 0, ran.map((line) => line.text));
      });

      await t.step(`/proxycmd from ${member} runs on the proxy once the key allows it`, async () => {
        const updated = await setProxyCommands(t.instance, keyId, true);
        t.require(updated.status === HttpStatus.ok, `turning /proxycmd on answered ${updated.status}`);
        await t.sleep(NetworkLimits.proxyCommandWindowMs);
        const proxySince = await t.env.log(proxy).mark();
        await t.consoleExpect(ConsoleCommands.proxyModlVersion, ProxyCommandMessages.sent, { server: member, timeoutMs: 15_000 });
        const running = await awaitLine(t, proxy, ConsolePatterns.networkProxyCommandRunning, proxySince);
        const match = ConsolePatterns.networkProxyCommandRunning.exec(running);
        t.check("the proxy names the command root", match?.[1] === "modl", running);
        t.check(`the proxy names ${member} as the sender`, match?.[2] === member, running);
        await awaitLine(t, proxy, ConsolePatterns.modlVersionReply, proxySince);
      });
    } finally {
      await setProxyCommands(t.instance, keyId, false);
    }

    try {
      await t.step(
        `revoking ${member}'s key disconnects it at once and it keeps warning`,
        async () => {
          const since = await t.env.log(member).mark();
          const revoked = await revokeServerKey(t.instance, keyId);
          t.require(revoked.status === HttpStatus.noContent, `revoking the key answered ${revoked.status}`);
          await awaitLine(t, member, ConsolePatterns.memberKeyRevoked, since);
          await awaitLine(t, member, ConsolePatterns.memberKeyRejected, since);
          const keys = await listServerKeys(t.instance);
          t.check(`the panel no longer lists ${member}'s key`, !keys.some((entry) => entry.id === keyId), keys);
          const listed = await awaitProxiedServer(t, member, ProxiedServerStates.noKey);
          t.check(`the proxy server ${member} is listed without a key`, listed.keyId === null, listed);
        },
        { timeoutMs: RECONFIGURE_TIMEOUT_MS, allowConsoleErrors: true },
      );
    } finally {
      await t.step(`${member} rejoins the network with a new key`, async () => rejoinWithNewKey(t, member),
        { timeoutMs: RECONFIGURE_TIMEOUT_MS, allowConsoleErrors: true });
    }
  },
});
