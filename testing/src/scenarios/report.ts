import { Endpoints, PlayerCommands, REPORT_TICKET_ID, reportAlert } from "../lib/contract";
import { defineScenario, type ScenarioContext } from "./framework";
import { linkedOwnerBot, ownerSession, passOptionalReplayStep } from "./support";

const REPORT_ALERT_TIMEOUT_MS = 15_000;

export const report = defineScenario({
  name: "report",
  description: "a player files a report through the GUI, online staff get the in-game report alert, and the ticket is visible through the panel API",
  topologies: ["standalone", "network", "remote"],
  async run(t: ScenarioContext) {
    await t.step("the owner has a panel session", async () => ownerSession(t));
    const staff = await t.step("a linked staff member is online", async () => linkedOwnerBot(t));
    const reporter = await t.step("the reporter joins", async () => t.bot(t.playerName("Rep")));
    const target = await t.step("the reported player joins", async () => t.bot(t.playerName("RepT")));

    let staffSince = staff.lastSeq;
    const ticketId = await t.step("the report is submitted through the menus", async () => {
      await reporter.openWindow(PlayerCommands.report(target.username));
      const category = reporter.findSlot(/Cheating/i);
      t.require(category, "the report menu has no Cheating category");
      await reporter.clickAndAwaitWindow(category.slot);
      await passOptionalReplayStep(reporter);
      const skip = reporter.findSlot(/Skip Details/i);
      t.require(skip, "the details menu has no Skip Details button");
      await reporter.clickAndAwaitWindow(skip.slot);
      const confirm = reporter.findSlot(/Confirm Report/i);
      t.require(confirm, "the confirm menu has no Confirm Report button");
      const since = reporter.lastSeq;
      staffSince = staff.lastSeq;
      await reporter.clickSlot(confirm.slot);
      const message = await reporter.awaitMessage(REPORT_TICKET_ID, 20_000, since);
      t.evidence("reporter chat", reporter.messagesSince(since).map((entry) => entry.text));
      const id = REPORT_TICKET_ID.exec(message.text)?.[1];
      t.require(id, `no ticket id in "${message.text}"`);
      return id;
    });

    await t.step("online staff get the report alert in game", async () => {
      const alert = await staff.awaitMessage(reportAlert(reporter.username, target.username), REPORT_ALERT_TIMEOUT_MS, staffSince).catch(() => null);
      t.evidence("staff chat", staff.messagesSince(staffSince).map((entry) => entry.text));
      t.check("the staff member sees the new report", alert !== null);
    });

    await t.step("the ticket is visible through the panel API", async () => {
      const result = await t.instance.api(Endpoints.ticket(ticketId));
      t.evidence("ticket", { status: result.status, body: result.json });
      t.check("GET ticket returns 200", result.status === 200, { status: result.status });
      t.check("the ticket references the reported player", result.text.toLowerCase().includes(target.username.toLowerCase()));
    });
  },
});
