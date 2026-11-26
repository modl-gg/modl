import { Endpoints } from "../lib/contract";
import { jsonField } from "../lib/http";
import { defineScenario, type ScenarioContext } from "./framework";

export const smoke = defineScenario({
  name: "smoke",
  description: "a player joins and chats; the panel serves the SPA, health and the public setup status",
  topologies: ["standalone", "network", "network-bungee", "remote"],
  async run(t: ScenarioContext) {
    const bot = await t.step("a player joins through the entry point", async () => t.bot(t.playerName("Smoke")));

    await t.step("the player is in the world and in the tab list", () => {
      const status = bot.status();
      t.check("connected with a world position", status.connected && status.position !== null, status);
      const players = bot.listPlayers().map((player) => player.username);
      t.check("tab list contains the player", players.includes(bot.username), players);
    });

    await t.step("chat is delivered", async () => {
      const marker = `smoke-${Date.now().toString(36)}`;
      const messages = await bot.runCommand(`hello ${marker}`, { expect: new RegExp(marker), timeoutMs: 6000 });
      t.check("own chat message is broadcast back", messages.some((message) => message.text.includes(marker)), messages.map((message) => message.text));
    });

    await t.step("the panel answers on its port", async () => {
      const http = t.instance.anonymousSession();
      const index = await http.request("/", { auth: "none", headers: { Accept: "text/html" } });
      t.check("GET / serves the panel HTML", index.status === 200 && /<html/i.test(index.text), { status: index.status, contentType: index.contentType });
      const health = await http.request(Endpoints.health, { auth: "none" });
      t.check("health reports UP", health.status === 200 && jsonField(health.json, "status") === "UP", { status: health.status, body: health.json });
      const setup = await http.request(Endpoints.setupStatus, { auth: "none" });
      t.check("setup status answers with a claimed flag", setup.status === 200 && typeof jsonField(setup.json, "claimed") === "boolean", setup.json);
    });
  },
});
