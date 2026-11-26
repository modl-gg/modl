import type { Page } from "playwright-core";
import type { VideoContext, VideoDefinition } from "../definition";
import type { StepLabel } from "../labels";
import type { CinematicPointer } from "../studio/pointer";
import type { Segment } from "../studio/segment";
import type { PanelBrowser } from "../studio/studio";
import { openRenderedSettings } from "./panel-navigation";
import { claimOffCamera, publishPanel } from "./stage";

const PANEL_ENTRY = { x: 640, y: 400 };
const STEP_TIMEOUT_MS = 20_000;

const CONTENT = {
  tagline: "Vanilla survival since 2019",
  address: "play.example.net",
  card: { title: "Vote for us", description: "Vote every day for in-game rewards", url: "https://example.net/vote" },
  link: { type: "Discord", label: "Discord", url: "https://discord.gg/example" },
  brandColor: "#22c55e",
  font: "Nunito",
  radius: "Large",
  css: '[data-modl="card"] { border-width: 2px; }',
} as const;

const LABELS = {
  homepage: { page: "Customizing-the-Public-Pages", text: "Settings > Public site > Homepage" },
  theme: { page: "Customizing-the-Public-Pages", text: "Settings > Public site > Theme" },
  css: { page: "Customizing-the-Public-Pages", text: "Settings > Public site > Custom CSS" },
  open: { page: "Customizing-the-Public-Pages", text: "Open homepage" },
} as const satisfies Record<string, StepLabel>;

const SPEECH = {
  intro: "Hi there, welcome to the public pages tutorial for modl. Let's make the homepage look like your server.",
  homepage: "The homepage is your server's public front page. Under Public site, Homepage, set a tagline and your server address.",
  card: "Cards link to a page, or open a knowledgebase category.",
  footer: "Footer links show with the icon of their type. Header, cards, and footer links save together.",
  theme: "Theme sets the colors, the font, and the corner radius. The preview shows the result before you save.",
  css: "For anything else, the owner can add custom CSS, using the styling hooks from the wiki.",
  open: "Open homepage shows the result.",
} as const;

async function choose(page: Page, pointer: CinematicPointer, combobox: string, option: string, scope = page.getByRole("dialog")): Promise<void> {
  await pointer.click(scope.getByRole("combobox", { name: combobox }));
  await pointer.click(page.getByRole("option", { name: option, exact: true }));
}

async function save(page: Page, pointer: CinematicPointer): Promise<void> {
  await pointer.click(page.getByRole("button", { name: "Save", exact: true }));
  await page.getByRole("status").filter({ hasText: "Saved" }).waitFor({ timeout: STEP_TIMEOUT_MS });
}

async function recordHomepage(context: VideoContext, browser: PanelBrowser, origin: string): Promise<Segment> {
  const take = await browser.take("homepage");
  const page = take.page;
  await openRenderedSettings(page, origin, "homepage", "Homepage");
  const pointer = take.pointer(PANEL_ENTRY);
  await take.start();
  await pointer.appear();
  await take.say(context.speech(SPEECH.intro));
  await take.untilSilent();
  await pointer.label(context.text(LABELS.homepage));
  await take.say(context.speech(SPEECH.homepage));
  await pointer.type(page.getByRole("textbox", { name: "Tagline" }), CONTENT.tagline);
  await pointer.type(page.getByRole("textbox", { name: "Server address" }), CONTENT.address);
  await pointer.click(page.getByRole("switch", { name: "Show player count" }));

  await take.say(context.speech(SPEECH.card));
  await pointer.click(page.getByRole("button", { name: "Add card", exact: true }));
  const card = page.getByRole("dialog");
  await pointer.type(card.getByRole("textbox", { name: "Title" }), CONTENT.card.title);
  await pointer.type(card.getByRole("textbox", { name: "Description" }), CONTENT.card.description);
  await choose(page, pointer, "Action type", "Link to URL");
  await pointer.type(card.getByRole("textbox", { name: "URL" }), CONTENT.card.url);
  await pointer.click(card.getByRole("button", { name: "Add", exact: true }));
  await card.waitFor({ state: "detached", timeout: STEP_TIMEOUT_MS });

  await take.say(context.speech(SPEECH.footer));
  const addLink = page.getByRole("button", { name: "Add link", exact: true });
  await addLink.scrollIntoViewIfNeeded();
  await page.evaluate("window.scrollBy(0, 240)");
  await pointer.hold(500);
  await pointer.click(addLink);
  const link = page.getByRole("dialog");
  await choose(page, pointer, "Type", CONTENT.link.type);
  await pointer.type(link.getByRole("textbox", { name: "Label" }), CONTENT.link.label);
  await pointer.type(link.getByRole("textbox", { name: "URL" }), CONTENT.link.url);
  await pointer.click(link.getByRole("button", { name: "Add", exact: true }));
  await link.waitFor({ state: "detached", timeout: STEP_TIMEOUT_MS });
  await save(page, pointer);
  await take.untilSilent();
  await pointer.settleLabel();
  const segment = await take.stop();
  await page.close();
  return segment;
}

async function recordTheme(context: VideoContext, browser: PanelBrowser, origin: string): Promise<Segment> {
  const take = await browser.take("theme");
  const page = take.page;
  await openRenderedSettings(page, origin, "theme", "Theme");
  const pointer = take.pointer(PANEL_ENTRY);
  await take.start();
  await pointer.appear();
  await pointer.label(context.text(LABELS.theme));
  await take.say(context.speech(SPEECH.theme));
  await pointer.type(page.getByRole("textbox", { name: "Brand color" }).last(), CONTENT.brandColor);
  await choose(page, pointer, "Font", CONTENT.font, page.locator("body"));
  await choose(page, pointer, "Corner radius", CONTENT.radius, page.locator("body"));
  await take.untilSilent();
  await save(page, pointer);
  await pointer.hold(800);
  await pointer.settleLabel();
  const segment = await take.stop();
  await page.close();
  return segment;
}

async function recordCss(context: VideoContext, browser: PanelBrowser, origin: string): Promise<{ segment: Segment; homepage: Page }> {
  const take = await browser.take("custom-css");
  const page = take.page;
  await openRenderedSettings(page, origin, "custom-css", "Custom CSS");
  const pointer = take.pointer(PANEL_ENTRY);
  await take.start();
  await pointer.appear();
  await pointer.label(context.text(LABELS.css));
  await take.say(context.speech(SPEECH.css));
  await pointer.type(page.getByRole("textbox", { name: "Custom CSS" }), CONTENT.css);
  await save(page, pointer);
  await take.untilSilent();
  await pointer.label(context.text(LABELS.open));
  await take.say(context.speech(SPEECH.open));
  const [homepage] = await Promise.all([page.waitForEvent("popup"), pointer.click(page.getByRole("button", { name: "Open homepage", exact: true }))]);
  await pointer.settleLabel();
  const segment = await take.stop();
  await page.close();
  return { segment, homepage };
}

async function recordResult(browser: PanelBrowser, homepage: Page): Promise<Segment> {
  await homepage.locator('[data-modl="card-grid"]').waitFor({ timeout: STEP_TIMEOUT_MS });
  await homepage.waitForTimeout(800);
  const take = browser.retake(homepage, "public-homepage");
  const pointer = take.pointer(PANEL_ENTRY);
  await take.start();
  await pointer.hold(2200);
  await homepage.evaluate('window.scrollTo({ top: document.body.scrollHeight, behavior: "smooth" })');
  await pointer.hold(2600);
  const segment = await take.stop();
  await homepage.close();
  return segment;
}

async function record(context: VideoContext): Promise<Segment[]> {
  const origin = await publishPanel(context);
  const browser = await context.studio.panelBrowser();
  await claimOffCamera(context, browser, origin);
  const homepage = await recordHomepage(context, browser, origin);
  const theme = await recordTheme(context, browser, origin);
  const css = await recordCss(context, browser, origin);
  const result = await recordResult(browser, css.homepage);
  return [homepage, theme, css.segment, result];
}

export const publicPages: VideoDefinition = {
  id: "public-pages",
  title: "Customize the public pages",
  wikiPage: "Customizing-the-Public-Pages",
  topology: "standalone",
  labels: Object.values(LABELS),
  presentation: "narrated",
  narration: Object.values(SPEECH),
  record,
};
