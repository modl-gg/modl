import { runDocker } from "../lib/docker";
import { CONTAINER_PORTS } from "../lib/topologies/compose";
import { defineScenario, type ScenarioContext } from "./framework";
import { memberNames } from "./support";

const REMOVED_BRIDGE_PORT = 25590;
const CONNECT_TIMEOUT_SECONDS = 5;
const REMOVED_BRIDGE_LOG_TAG = /\[bridge\]/;

async function canConnect(container: string, host: string, port: number): Promise<boolean> {
  const probe = `exec 3<>/dev/tcp/${host}/${String(port)}`;
  const result = await runDocker(["exec", container, "timeout", String(CONNECT_TIMEOUT_SECONDS), "bash", "-c", probe], {
    allowFailure: true,
    timeoutMs: 30_000,
  });
  return result.code === 0;
}

export const noBridgePort = defineScenario({
  name: "no-bridge-port",
  description: "the proxy no longer listens on the old bridge port 25590 and prints no bridge lines: servers behind it reach modl only through the panel port",
  topologies: ["network", "network-bungee"],
  async run(t: ScenarioContext) {
    const member = memberNames(t)[0];
    t.require(member, "the topology has no server behind the proxy");
    const proxy = t.coreServer;
    const container = t.env.server(member).container;

    await t.step(`${member} reaches the panel port of the proxy`, async () => {
      t.check(`${member} connects to ${proxy}:${String(CONTAINER_PORTS.http)}`, await canConnect(container, proxy, CONTAINER_PORTS.http));
    });

    await t.step(`${member} cannot connect to ${proxy}:${String(REMOVED_BRIDGE_PORT)}`, async () => {
      t.check(`a TCP connect to ${proxy}:${String(REMOVED_BRIDGE_PORT)} is refused`, !(await canConnect(container, proxy, REMOVED_BRIDGE_PORT)));
    });

    await t.step("no server printed a bridge line", async () => {
      for (const server of t.env.serverNames()) {
        const lines = await t.env.log(server).find(REMOVED_BRIDGE_LOG_TAG);
        t.check(`${server} printed no [bridge] line`, lines.length === 0, lines.map((line) => line.text));
      }
    });
  },
});
