import { join } from "node:path";
import { Endpoints, UpdateEnums, UpdateErrors, UpdateMessages } from "../lib/contract";
import { apiErrorCode, HttpStatus, jsonField, jsonString } from "../lib/http";
import { releaseVersion } from "../lib/release-fixtures";
import { ReleaseMirror } from "../lib/topologies/release-mirror";
import { writeText } from "../lib/util";
import { defineScenario, type ScenarioContext } from "./framework";
import {
  awaitConsole,
  checkNow,
  dataDirectory,
  install,
  needsFixtureAndJarBackend,
  needsUpdateFixture,
  offer,
  RESTART_TIMEOUT_MS,
  updateStatus,
  withUpdateLab,
} from "./update-support";

const BACKEND = "backend";
const SERVERS = [BACKEND, "paper"] as const;

export const updateCheck = defineScenario({
  name: "update-check",
  description: "the release check shows the offer, notifies once across restarts, honours dismissal, and sends nothing when switched off",
  topologies: ["remote"],
  unsupportedBy: needsFixtureAndJarBackend,
  async run(t: ScenarioContext) {
    await withUpdateLab(t, SERVERS, async (lab) => {
      const since = await t.env.log(BACKEND).mark();
      await t.step("a newer patch is offered with one notice", async () => {
        const release = await offer(t, lab.mirror, "patch");
        t.check("the update can be installed", jsonString(release, "installAvailability") === UpdateEnums.available, release);
        const status = await updateStatus(t);
        t.check("the deployment is the server jar", jsonString(status, "deployment") === UpdateEnums.jar, status);
        await awaitConsole(t, BACKEND, UpdateMessages.available, since);
      });

      await t.step("the dismissal is stored", async () => {
        const result = await t.instance.api(Endpoints.updatesDismiss, { method: "POST", json: { version: releaseVersion("patch") } });
        t.require(result.status === HttpStatus.ok, `the dismissal returned ${result.status}`);
        t.check("the release is dismissed", jsonField(result.json, "dismissed") === true, result.json);
      });

      await t.step(
        "a restart keeps the dismissal and does not notify again",
        async () => {
          await t.restart(BACKEND);
          const status = await checkNow(t);
          t.check("the release stays dismissed", jsonField(status, "dismissed") === true, status);
          const notices = await t.env.log(BACKEND).find(UpdateMessages.available, since);
          t.check("the notice was printed once", notices.length === 1, notices.map((line) => line.text));
        },
        { timeoutMs: RESTART_TIMEOUT_MS, allowConsoleErrors: true },
      );

      await t.step("a newer version is shown again", async () => {
        const release = await offer(t, lab.mirror, "minor");
        t.check("it needs the plugins updated", jsonField(release, "requiresPluginUpdate") === true, release);
        t.check("the new version is not dismissed", jsonField(await updateStatus(t), "dismissed") !== true);
      });

      await t.step(
        "MODL_UPDATE_CHECK=false sends no request at all",
        async () => {
          writeText(join(dataDirectory(t), ".env"), "MODL_UPDATE_CHECK=false\n");
          const requestsSince = await lab.mirror.mark();
          await t.restart(BACKEND);
          const refused = await t.instance.api(Endpoints.updatesCheck, { method: "POST" });
          t.check(
            "a manual check is refused",
            refused.status === HttpStatus.conflict && apiErrorCode(refused) === UpdateErrors.checkDisabled,
            refused.json,
          );
          const requests = await lab.mirror.requests(requestsSince);
          t.check("the mirror was not asked", requests.length === 0, requests);
        },
        { timeoutMs: RESTART_TIMEOUT_MS, allowConsoleErrors: true },
      );
    });
  },
});

export const updateDocker = defineScenario({
  name: "update-docker",
  description: "the compose deployment reports Docker, refuses Update now and names the image tag of the offered release",
  topologies: ["remote"],
  unsupportedBy: (options) =>
    needsUpdateFixture(options) ?? (options.remoteBackend === "compose" ? null : "needs the compose backend (--backend compose)"),
  async run(t: ScenarioContext) {
    await t.step("the compose backend built from source is a Docker deployment", async () => {
      await t.instance.ensureOwnerSession();
      const status = await updateStatus(t);
      t.check("the deployment is Docker", jsonString(status, "deployment") === UpdateEnums.docker, status);
    });

    const mirror = new ReleaseMirror(t.env.state.project);
    try {
      await t.step("another line names the signed image digest and cannot be installed from the panel", async () => {
        mirror.clear();
        const release = await offer(t, mirror, "minor");
        t.check(
          "the image is pinned to the signed digest",
          /^[^@]+@sha256:[0-9a-f]{64}$/u.test(jsonString(release, "imageReference") ?? "")
            && jsonString(release, "imageReference")?.startsWith(`${releaseVersion("minor")}@`) === true,
          release,
        );
        t.check("the install is Docker's", jsonString(release, "installAvailability") === UpdateEnums.dockerInstall, release);
        const refused = await install(t, "minor");
        t.check(
          "Update now is refused",
          refused.status === HttpStatus.conflict && apiErrorCode(refused) === UpdateErrors.notInstallable,
          refused.json,
        );
      });
    } finally {
      mirror.clear();
    }
  },
});
