export const REVIEW_PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>modl review</title>
<style>
:root { --bg: #f6f6f7; --card: #fff; --text: #18181b; --muted: #71717a; --border: #e4e4e7; --accent: #2563eb; --accent-text: #fff; --new: #16a34a; --changed: #d97706; --removed: #dc2626; color-scheme: light; }
@media (prefers-color-scheme: dark) { :root { --bg: #0d0d0f; --card: #18181b; --text: #fafafa; --muted: #a1a1aa; --border: #27272a; --accent: #3b82f6; color-scheme: dark; } }
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--text); font: 14px/1.45 ui-sans-serif, system-ui, sans-serif; }
header { position: sticky; top: 0; z-index: 2; display: flex; gap: 12px; align-items: center; padding: 12px 20px; background: var(--card); border-bottom: 1px solid var(--border); }
header h1 { font-size: 15px; margin: 0; font-weight: 600; }
header .count { color: var(--muted); }
header .spacer { flex: 1; }
main { max-width: 1600px; margin: 0 auto; padding: 20px; display: grid; gap: 20px; }
button { font: inherit; border: 1px solid var(--border); background: var(--card); color: var(--text); border-radius: 8px; padding: 6px 12px; cursor: pointer; }
button.primary { background: var(--accent); border-color: var(--accent); color: var(--accent-text); }
button:disabled { opacity: .5; cursor: default; }
.notice { padding: 10px 14px; border: 1px solid var(--border); border-radius: 10px; background: var(--card); }
.card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; overflow: hidden; }
.card-head { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; padding: 12px 14px; border-bottom: 1px solid var(--border); }
.card-head h2 { font-size: 14px; margin: 0; font-weight: 600; }
.pill { font-size: 12px; padding: 1px 8px; border-radius: 999px; border: 1px solid currentColor; }
.pill.new { color: var(--new); } .pill.changed { color: var(--changed); } .pill.removed { color: var(--removed); }
.meta { color: var(--muted); font-size: 12px; }
.views { display: inline-flex; border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
.views button { border: 0; border-radius: 0; padding: 4px 10px; }
.views button[aria-pressed="true"] { background: var(--accent); color: var(--accent-text); }
.images { display: grid; gap: 10px; padding: 14px; grid-template-columns: 1fr; align-items: start; }
.images.pair { grid-template-columns: 1fr 1fr; }
.images figure { margin: 0; }
.images figcaption { color: var(--muted); font-size: 12px; margin-bottom: 4px; }
.images img { display: block; max-width: 100%; border: 1px solid var(--border); border-radius: 6px; }
.feedback { display: grid; gap: 8px; padding: 0 14px 14px; }
.feedback ul { margin: 0; padding-left: 18px; }
.feedback textarea { width: 100%; min-height: 56px; resize: vertical; font: inherit; color: var(--text); background: var(--bg); border: 1px solid var(--border); border-radius: 8px; padding: 8px; }
.row { display: flex; gap: 8px; justify-content: flex-end; }
.empty { text-align: center; color: var(--muted); padding: 80px 0; }
details summary { cursor: pointer; }
@media (max-width: 900px) { .images.pair { grid-template-columns: 1fr; } }
</style>
</head>
<body>
<header><h1>Review</h1><span class="count" id="count"></span><span class="spacer"></span><button class="primary" id="approve-all" disabled>Approve all</button></header>
<main id="main"></main>
<script>
const main = document.getElementById("main");
const count = document.getElementById("count");
const approveAll = document.getElementById("approve-all");
const views = new Map();
let state = null;

async function post(path, body) {
  const response = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json", "X-Modl-Review": "1" }, body: JSON.stringify(body) });
  if (!response.ok) throw new Error(await response.text());
  state = await response.json();
  render();
}

function el(tag, attributes, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attributes || {})) {
    if (key === "onclick") node.addEventListener("click", value);
    else node.setAttribute(key, value);
  }
  for (const child of children) node.append(child);
  return node;
}

function image(kind, entry, caption) {
  const src = "/files/" + kind + "/" + encodeURIComponent(entry.id) + "?t=" + encodeURIComponent(state.generatedAt);
  return el("figure", {}, el("figcaption", {}, caption), el("a", { href: src, target: "_blank" }, el("img", { src, alt: caption, loading: "lazy" })));
}

function defaultView(entry) {
  return entry.status === "changed" ? "pair" : entry.status === "new" ? "after" : "before";
}

function choices(entry) {
  return entry.status === "changed" ? [["pair", "Side by side"], ["before", "Before"], ["after", "After"], ["diff", "Diff"]] : [];
}

function card(entry) {
  const view = views.get(entry.id) || defaultView(entry);
  const images = el("div", { class: view === "pair" ? "images pair" : "images" });
  if (view === "pair") images.append(image("baseline", entry, "Before"), image("current", entry, "After"));
  if (view === "before") images.append(image("baseline", entry, "Before"));
  if (view === "after") images.append(image("current", entry, "After"));
  if (view === "diff") images.append(image("diff", entry, "Diff"));
  const toggle = el("div", { class: "views" });
  for (const [key, label] of choices(entry)) {
    toggle.append(el("button", { "aria-pressed": String(key === view), onclick: () => { views.set(entry.id, key); render(); } }, label));
  }
  const text = el("textarea", { placeholder: "What should change?" });
  const sent = el("ul", {}, ...entry.comments.map((comment) => el("li", {}, comment.text)));
  const changed = entry.status === "changed" ? (entry.ratio * 100).toFixed(2) + "% of pixels" : "";
  return el("section", { class: "card" },
    el("div", { class: "card-head" },
      el("h2", {}, entry.screen),
      el("span", { class: "meta" }, entry.variant.replace("-", " \\u00b7 ")),
      el("span", { class: "pill " + entry.status }, entry.status),
      el("span", { class: "meta" }, changed),
      el("span", { style: "flex:1" }),
      toggle,
      el("button", { class: "primary", onclick: () => post("/api/approve", { ids: [entry.id] }) }, "Approve")),
    images,
    el("div", { class: "feedback" },
      entry.comments.length ? sent : "",
      text,
      el("div", { class: "row" }, el("button", { onclick: () => post("/api/comment", { id: entry.id, text: text.value }) }, "Send comment"))));
}

function render() {
  main.replaceChildren();
  const entries = state.entries;
  count.textContent = entries.length ? entries.length + " pending" : "";
  approveAll.disabled = entries.length === 0;
  if (state.datasetRebuilt && entries.length) main.append(el("div", { class: "notice" }, "The seed data was rebuilt, so ids may differ on every screen."));
  if (state.problems.length) {
    const list = el("ul", {}, ...state.problems.map((problem) => el("li", {}, problem.id + ": " + problem.problems.join("; "))));
    main.append(el("details", { class: "notice" }, el("summary", {}, state.problems.length + " screens had problems"), list));
  }
  if (!entries.length) {
    main.append(el("div", { class: "empty" }, "Nothing to review"));
    return;
  }
  for (const entry of entries) main.append(card(entry));
}

approveAll.addEventListener("click", () => post("/api/approve", { ids: state.entries.map((entry) => entry.id) }));
fetch("/api/state").then((response) => response.json()).then((value) => { state = value; render(); });
</script>
</body>
</html>
`;
