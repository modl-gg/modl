import { Endpoints, LOGIN_LINK_URL, PlayerCommands } from "../lib/contract";
import { jsonField, jsonString } from "../lib/http";
import { settings } from "../lib/settings";
import { offlineUuid } from "../lib/util";
import { defineScenario, type ScenarioContext } from "./framework";
import { isLinkedTo } from "./support";

function redactToken(text: string): string {
  return text.replace(/token=([A-Za-z0-9._~-]{4})[A-Za-z0-9._~-]*/g, "token=$1...");
}

export const panelLinkLogin = defineScenario({
  name: "panel-link-login",
  description: "claim, owner sign-in code or email-code login, link the Minecraft account with /modl link, and log in with the /modl panel link",
  topologies: ["standalone", "network", "remote"],
  async run(t: ScenarioContext) {
    await t.step("the owner gets a new panel session through the setup code or a console login code", async () => {
      const { path, profile } = await t.instance.newOwnerSession();
      t.evidence("session", { path, profile });
      t.check("the session belongs to the owner", profile.email.toLowerCase() === t.instance.ownerEmail.toLowerCase(), profile);
      t.check("the instance is claimed", await t.instance.isClaimed());
    });

    const code = await t.step("the panel issues a Minecraft link code", async () => {
      const unlink = await t.instance.unlinkMinecraft();
      t.evidence("unlink before linking", { status: unlink.status });
      const link = await t.instance.createMinecraftLinkCode();
      t.evidence("link code", { command: link.command, expiresInSeconds: link.expiresInSeconds });
      t.check("the command shown is /modl link <code>", link.command.includes(PlayerCommands.link(link.code).slice(1)), link.command);
      t.check("the code expires", link.expiresInSeconds > 0 && link.expiresInSeconds <= 3600, link.expiresInSeconds);
      return link.code;
    });

    const bot = await t.step("the owner's player joins", async () => t.bot(settings().ownerPlayer));

    await t.step("/modl link <code> links the account to the owner", async () => {
      const replies = await bot.runCommand(PlayerCommands.link(code), { timeoutMs: 8000 });
      t.evidence("replies", replies.map((message) => message.text));
      const profile = await t.instance.profile();
      t.check("the profile carries the player's offline UUID", isLinkedTo(profile, bot.username), {
        expected: offlineUuid(bot.username),
        profile,
      });
      t.check("the profile carries the player's name", profile?.minecraftUsername === bot.username, profile);
    });

    await t.step("a used link code is rejected", async () => {
      const replies = await bot.runCommand(PlayerCommands.link(code), { timeoutMs: 8000 });
      t.expectMatch(
        "reusing the code is refused",
        replies.map((message) => message.text),
        /invalid|expired|not valid|unknown|already/i,
      );
    });

    const loginToken = await t.step("/modl panel returns a one-time login link", async () => {
      const since = bot.lastSeq;
      bot.chat(PlayerCommands.panelLogin);
      const message = await bot.awaitMessage(LOGIN_LINK_URL, 10_000, since);
      const match = LOGIN_LINK_URL.exec(message.json) ?? LOGIN_LINK_URL.exec(message.text);
      t.require(match?.[1], "the /modl panel reply carries no login link");
      t.evidence("login link", { url: redactToken(match[0]), text: redactToken(message.text) });
      t.check("the link points at the panel URL", match[0].startsWith(`${t.instance.baseUrl}/`), { url: redactToken(match[0]), panel: t.instance.baseUrl });
      return match[1];
    });

    await t.step("the link token logs in through /v1/panel/auth/link", async () => {
      const { result, session } = await t.instance.consumeLoginLink(loginToken);
      t.evidence("consume", { status: result.status, body: result.json, setCookies: result.setCookies });
      t.check("consuming the token succeeds", result.status === 200 && jsonField(result.json, "success") !== false, { status: result.status });
      const profile = await session.request(Endpoints.profile);
      t.check("the new session is the owner's", jsonString(profile.json, "email")?.toLowerCase() === t.instance.ownerEmail.toLowerCase(), {
        status: profile.status,
        body: profile.json,
      });
    });

    await t.step("the login link is single use", async () => {
      const { result } = await t.instance.consumeLoginLink(loginToken);
      t.check("a second use is rejected with 401", result.status === 401, { status: result.status, body: result.json });
    });
  },
});
