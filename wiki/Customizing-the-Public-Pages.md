# Customizing the Public Pages

The public pages are the homepage, the knowledgebase and its articles, the ticket and appeal forms, the player ticket view, and evidence upload. The staff panel, sign-in, and setup pages always keep the default look.

https://github.com/user-attachments/assets/db97adcc-5b50-4aef-bc37-4eb4ea7f3427

1. Settings > Public site > Homepage: tagline, server address, cards, and footer links.
2. Settings > Public site > Theme: colors, font, and corner radius.
3. Settings > Public site > Custom CSS (owner only) for anything else, using the [styling hooks](#styling-hooks).
4. Save, then Open homepage to see the result.

## Homepage

Panel > Settings > Public site > Homepage. Viewing needs `admin.settings.view.content`, changing needs `admin.settings.modify.content`. Header, cards, and footer links save together with one Save, and changes are audited. A setting left empty or off does not show. The server name and the homepage icon come from Panel > Settings > General > Panel.

| Setting | Values | Effect |
|---|---|---|
| Tagline | one line, at most 160 characters | line under the server name |
| Server address | at most 255 characters, no spaces | address chip; a click copies it |
| Show player count | off (default), on | players online on every proxy or standalone server connected to modl right now (servers behind a proxy are counted once, through the proxy), refreshed every 30 seconds; no names are shown |
| Cards | at most 50; title, optional description, icon, a link (`http:`, `https:`, or a path such as `/appeal`), or a knowledgebase category, shown or hidden | rows under the search, two columns on wide screens; a category row opens its article list in place; without cards the homepage shows Appeal punishment, Apply for staff, and Contact us |
| Footer links | at most 8; label (at most 48 characters), `http:` or `https:` address, type Discord, Store, Website, Vote, or Other | footer links with the icon of their type, next to the server name |

A refused save names `HOMEPAGE_TEXT_INVALID` (multi-line or too long text), `HOMEPAGE_LINK_URL_INVALID` (a footer link that is not an `http:` or `https:` address), `HOMEPAGE_TOO_MANY_LINKS` or `HOMEPAGE_CARD_INVALID` (a card without a title, or a link card without a valid address, or a category card without a category). Another staff member's save in between answers 409; reload to see it.

## Theme

Panel > Settings > Public site > Theme. Viewing needs `admin.settings.view.content`, changing needs `admin.settings.modify.content`. The page previews the light and dark look before you save. Changes are audited.

| Setting | Values | Effect |
|---|---|---|
| Brand color | `#rrggbb`, empty for the default | buttons, links, focus rings, and the modl wordmark; text on it turns black or white, whichever reads better |
| Accent color | `#rrggbb`, empty for the default | homepage card icons and the glow behind the homepage header (the brand color when empty) |
| Background color | `#rrggbb`, empty for the default | page background: a light tint of it in light mode and a dark shade in dark mode, so text stays readable |
| Background image | PNG, JPEG, GIF, or WebP, at most 10 MB and the upload limit | fixed layer behind the page (`[data-modl="page"]::before`) under a veil of the background color |
| Font | System (default), Inter, Nunito, Lora, Space Grotesk | bundled with modl, downloaded by visitors only when chosen |
| Corner radius | None, Small, Medium (default), Large | cards, buttons, and inputs |
| Default color scheme | System (default), Light, Dark | used until a visitor picks light or dark with the homepage toggle; that choice stays in their browser |

The modl.gg import does not bring a theme or custom CSS.

## Custom CSS

Panel > Settings > Public site > Custom CSS, owner only. At most 1 MiB, audited. modl serves it at `/v1/public/theme.css` (`text/css`, cached per content revision) and loads it only on the public pages, after the theme. It is not layered, so its rules win over the built-in styles and the theme without `!important`.

Saving is refused when the CSS contains:

| Content (also when written with CSS escapes or split by comments) | Error code |
|---|---|
| `@import` | `CUSTOM_CSS_IMPORT` |
| `</style` | `CUSTOM_CSS_MARKUP` |
| `javascript:` or `vbscript:` URLs, `expression(`, `behavior:`, `-moz-binding` | `CUSTOM_CSS_SCRIPT` |
| NUL characters | `CUSTOM_CSS_INVALID_CHARACTER` |
| more than 1 MiB (UTF-8) | `CUSTOM_CSS_TOO_LARGE` |

These checks are a backstop; the Content Security Policy is the boundary, and custom CSS does not change it. Images in `url()` load from this server, the storage backend, and `https:` addresses. Fonts load from this server, `data:` URLs, and `https:` addresses (with `@font-face`, since `@import` is refused).

The theme sets CSS variables that custom CSS can reuse as `hsl(var(--primary))`: `--primary`, `--primary-foreground`, `--accent`, `--background`, `--foreground`, `--card`, `--muted-foreground`, `--border`; plus `--radius` and `--font-sans`.

### Safe mode

Open a public page with `?nocss` at the end of its address (for example `https://panel.example.com/?nocss`) to see it without custom CSS until the tab is reloaded without it. The staff panel never loads custom CSS, so the editor always works.

### Example: Minecraft theme

A complete theme that makes the public pages look like Minecraft: a sky with drifting clouds over a row of grass blocks, a dirt background, a stone logo for the server name, a splash text tagline, menu buttons for the homepage cards, and inventory-style forms. Paste it into Custom CSS as is. It uses only the styling hooks below, the block textures are original pixel art embedded as SVG, and the font is Pixelify Sans (SIL Open Font License) from jsDelivr.

<details>
<summary>Minecraft theme CSS</summary>

```css
@font-face {
  font-family: "MC Pixel";
  src: url("https://cdn.jsdelivr.net/npm/@fontsource/pixelify-sans@5.3.0/files/pixelify-sans-latin-400-normal.woff2") format("woff2");
  font-weight: 400;
  font-display: swap;
}

@font-face {
  font-family: "MC Pixel";
  src: url("https://cdn.jsdelivr.net/npm/@fontsource/pixelify-sans@5.3.0/files/pixelify-sans-latin-700-normal.woff2") format("woff2");
  font-weight: 700;
  font-display: swap;
}

[data-modl="page"] {
  --mc-dirt:url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' shape-rendering='crispEdges'><path fill='%2379553a' d='M0 0h1v1h-1zM5 0h1v1h-1zM15 0h1v1h-1zM3 1h1v1h-1zM7 1h1v1h-1zM10 1h1v1h-1zM12 1h1v1h-1zM15 1h1v1h-1zM3 2h1v1h-1zM6 2h1v1h-1zM9 2h1v1h-1zM12 2h1v1h-1zM1 3h1v1h-1zM13 3h1v1h-1zM11 4h2v1h-2zM5 5h2v1h-2zM14 5h1v1h-1zM0 6h1v1h-1zM4 6h3v1h-3zM9 6h1v1h-1zM12 6h1v1h-1zM8 7h2v1h-2zM14 7h1v1h-1zM0 8h1v1h-1zM5 8h1v1h-1zM10 8h3v1h-3zM4 9h2v1h-2zM2 10h2v1h-2zM8 10h2v1h-2zM0 11h1v1h-1zM2 11h1v1h-1zM5 11h2v1h-2zM13 11h3v1h-3zM0 12h2v1h-2zM14 12h1v1h-1zM0 13h1v1h-1zM3 13h2v1h-2zM0 14h1v1h-1zM11 14h4v1h-4zM0 15h1v1h-1zM4 15h1v1h-1zM15 15h1v1h-1z'/><path fill='%236c4a32' d='M1 0h1v1h-1zM3 0h1v1h-1zM10 0h2v1h-2zM14 0h1v1h-1zM8 1h2v1h-2zM3 3h1v1h-1zM6 3h1v1h-1zM0 5h2v1h-2zM4 5h1v1h-1zM8 5h1v1h-1zM3 6h1v1h-1zM10 7h1v1h-1zM13 7h1v1h-1zM15 7h1v1h-1zM3 8h2v1h-2zM9 8h1v1h-1zM13 8h1v1h-1zM2 9h2v1h-2zM7 9h1v1h-1zM11 9h1v1h-1zM4 10h1v1h-1zM8 12h1v1h-1zM7 13h3v1h-3zM12 13h1v1h-1zM2 14h1v1h-1zM2 15h1v1h-1z'/><path fill='%23866043' d='M2 0h1v1h-1zM4 0h1v1h-1zM7 0h1v1h-1zM9 0h1v1h-1zM12 0h1v1h-1zM0 1h1v1h-1zM2 1h1v1h-1zM13 1h2v1h-2zM0 2h1v1h-1zM4 2h2v1h-2zM7 2h2v1h-2zM11 2h1v1h-1zM13 2h2v1h-2zM4 3h1v1h-1zM7 3h1v1h-1zM9 3h1v1h-1zM11 3h1v1h-1zM14 3h2v1h-2zM0 4h2v1h-2zM4 4h2v1h-2zM7 4h2v1h-2zM13 4h1v1h-1zM15 4h1v1h-1zM9 5h2v1h-2zM15 5h1v1h-1zM7 6h2v1h-2zM11 6h1v1h-1zM13 6h1v1h-1zM15 6h1v1h-1zM0 7h3v1h-3zM11 7h1v1h-1zM8 8h1v1h-1zM0 9h2v1h-2zM10 9h1v1h-1zM12 9h1v1h-1zM14 9h1v1h-1zM1 10h1v1h-1zM6 10h1v1h-1zM1 11h1v1h-1zM7 11h1v1h-1zM9 11h1v1h-1zM2 12h1v1h-1zM5 12h2v1h-2zM9 12h1v1h-1zM13 12h1v1h-1zM15 13h1v1h-1zM1 14h1v1h-1zM5 14h2v1h-2zM8 14h1v1h-1zM15 14h1v1h-1zM1 15h1v1h-1zM5 15h2v1h-2zM8 15h1v1h-1zM10 15h3v1h-3zM14 15h1v1h-1z'/><path fill='%23593d29' d='M6 0h1v1h-1zM8 0h1v1h-1zM5 1h1v1h-1zM1 2h2v1h-2zM8 3h1v1h-1zM6 4h1v1h-1zM14 4h1v1h-1zM2 5h1v1h-1zM10 6h1v1h-1zM3 7h1v1h-1zM12 7h1v1h-1zM1 8h2v1h-2zM6 8h1v1h-1zM8 9h1v1h-1zM13 9h1v1h-1zM3 11h2v1h-2zM3 14h1v1h-1zM13 15h1v1h-1z'/><path fill='%239b6e4b' d='M13 0h1v1h-1zM6 1h1v1h-1zM11 1h1v1h-1zM10 2h1v1h-1zM15 2h1v1h-1zM0 3h1v1h-1zM5 3h1v1h-1zM10 3h1v1h-1zM12 3h1v1h-1zM2 4h1v1h-1zM10 4h1v1h-1zM3 5h1v1h-1zM7 5h1v1h-1zM11 5h3v1h-3zM1 6h1v1h-1zM4 7h4v1h-4zM7 8h1v1h-1zM14 8h1v1h-1zM6 9h1v1h-1zM0 10h1v1h-1zM5 10h1v1h-1zM7 10h1v1h-1zM10 10h1v1h-1zM12 10h4v1h-4zM3 12h2v1h-2zM7 12h1v1h-1zM10 12h3v1h-3zM15 12h1v1h-1zM1 13h1v1h-1zM6 13h1v1h-1zM10 13h2v1h-2zM13 13h1v1h-1zM7 14h1v1h-1zM9 14h2v1h-2zM3 15h1v1h-1zM7 15h1v1h-1zM9 15h1v1h-1z'/><path fill='%23b9855c' d='M1 1h1v1h-1zM4 1h1v1h-1zM2 3h1v1h-1zM3 4h1v1h-1zM9 4h1v1h-1zM2 6h1v1h-1zM14 6h1v1h-1zM15 8h1v1h-1zM9 9h1v1h-1zM15 9h1v1h-1zM11 10h1v1h-1zM8 11h1v1h-1zM10 11h3v1h-3zM2 13h1v1h-1zM5 13h1v1h-1zM14 13h1v1h-1zM4 14h1v1h-1z'/></svg>");
  --mc-stone:url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' shape-rendering='crispEdges'><path fill='%237f7f7f' d='M0 0h2v1h-2zM3 0h3v1h-3zM7 0h2v1h-2zM14 0h1v1h-1zM2 1h2v1h-2zM6 1h1v1h-1zM11 1h2v1h-2zM14 1h2v1h-2zM0 2h3v1h-3zM7 2h1v1h-1zM13 2h1v1h-1zM5 3h1v1h-1zM7 3h1v1h-1zM9 3h1v1h-1zM6 4h2v1h-2zM11 4h1v1h-1zM13 4h1v1h-1zM5 5h1v1h-1zM7 5h1v1h-1zM1 6h1v1h-1zM3 6h1v1h-1zM5 6h1v1h-1zM7 6h2v1h-2zM5 7h2v1h-2zM11 7h1v1h-1zM14 7h1v1h-1zM4 8h1v1h-1zM6 8h1v1h-1zM12 8h1v1h-1zM0 9h1v1h-1zM5 9h3v1h-3zM12 9h1v1h-1zM15 9h1v1h-1zM0 10h1v1h-1zM5 10h1v1h-1zM9 10h1v1h-1zM13 10h1v1h-1zM0 11h1v1h-1zM3 11h2v1h-2zM6 11h2v1h-2zM9 11h1v1h-1zM15 11h1v1h-1zM0 12h1v1h-1zM5 12h2v1h-2zM8 12h3v1h-3zM15 12h1v1h-1zM3 13h2v1h-2zM7 13h1v1h-1zM9 13h1v1h-1zM14 13h1v1h-1zM1 14h1v1h-1zM8 14h1v1h-1zM11 14h3v1h-3zM1 15h1v1h-1zM6 15h2v1h-2zM12 15h1v1h-1z'/><path fill='%239a9a9a' d='M2 0h1v1h-1zM0 1h2v1h-2zM4 2h2v1h-2zM0 3h1v1h-1zM4 3h1v1h-1zM6 3h1v1h-1zM14 3h1v1h-1zM0 5h1v1h-1zM9 5h1v1h-1zM6 6h1v1h-1zM1 7h1v1h-1zM3 7h1v1h-1zM9 7h1v1h-1zM8 8h1v1h-1zM4 9h1v1h-1zM11 9h1v1h-1zM13 9h1v1h-1zM3 10h1v1h-1zM11 10h1v1h-1zM10 11h1v1h-1zM11 12h1v1h-1zM11 13h1v1h-1zM5 14h1v1h-1zM0 15h1v1h-1z'/><path fill='%23747474' d='M6 0h1v1h-1zM11 0h1v1h-1zM8 1h2v1h-2zM3 2h1v1h-1zM8 2h3v1h-3zM15 2h1v1h-1zM3 3h1v1h-1zM11 3h1v1h-1zM13 3h1v1h-1zM1 4h1v1h-1zM5 4h1v1h-1zM8 4h1v1h-1zM14 4h2v1h-2zM2 5h1v1h-1zM4 5h1v1h-1zM10 5h2v1h-2zM13 5h2v1h-2zM2 6h1v1h-1zM4 6h1v1h-1zM10 6h1v1h-1zM14 6h2v1h-2zM2 7h1v1h-1zM10 7h1v1h-1zM12 7h1v1h-1zM15 7h1v1h-1zM0 8h1v1h-1zM3 8h1v1h-1zM7 8h1v1h-1zM9 8h1v1h-1zM11 8h1v1h-1zM14 8h2v1h-2zM2 9h1v1h-1zM2 10h1v1h-1zM12 10h1v1h-1zM5 11h1v1h-1zM13 11h1v1h-1zM7 12h1v1h-1zM12 12h1v1h-1zM14 12h1v1h-1zM0 13h1v1h-1zM6 13h1v1h-1zM8 13h1v1h-1zM13 13h1v1h-1zM15 13h1v1h-1zM6 14h1v1h-1zM9 14h2v1h-2zM14 14h1v1h-1zM8 15h1v1h-1zM15 15h1v1h-1z'/><path fill='%238f8f8f' d='M9 0h1v1h-1zM13 0h1v1h-1zM13 1h1v1h-1zM6 2h1v1h-1zM12 2h1v1h-1zM14 2h1v1h-1zM1 3h1v1h-1zM10 3h1v1h-1zM15 3h1v1h-1zM4 4h1v1h-1zM9 4h1v1h-1zM1 5h1v1h-1zM3 5h1v1h-1zM6 5h1v1h-1zM12 5h1v1h-1zM9 6h1v1h-1zM12 6h2v1h-2zM0 7h1v1h-1zM4 7h1v1h-1zM13 7h1v1h-1zM1 8h1v1h-1zM5 8h1v1h-1zM3 9h1v1h-1zM1 10h1v1h-1zM6 10h3v1h-3zM10 10h1v1h-1zM14 10h2v1h-2zM1 11h2v1h-2zM11 11h2v1h-2zM14 11h1v1h-1zM1 12h1v1h-1zM3 12h1v1h-1zM2 13h1v1h-1zM5 13h1v1h-1zM10 13h1v1h-1zM12 13h1v1h-1zM2 14h3v1h-3zM15 14h1v1h-1zM4 15h1v1h-1zM9 15h1v1h-1zM14 15h1v1h-1z'/><path fill='%236b6b6b' d='M10 0h1v1h-1zM12 0h1v1h-1zM4 1h1v1h-1zM7 1h1v1h-1zM11 2h1v1h-1zM8 3h1v1h-1zM12 3h1v1h-1zM0 4h1v1h-1zM2 4h2v1h-2zM10 4h1v1h-1zM12 4h1v1h-1zM8 5h1v1h-1zM15 5h1v1h-1zM0 6h1v1h-1zM11 6h1v1h-1zM7 7h1v1h-1zM2 8h1v1h-1zM13 8h1v1h-1zM1 9h1v1h-1zM8 9h1v1h-1zM4 10h1v1h-1zM8 11h1v1h-1zM2 12h1v1h-1zM1 13h1v1h-1zM0 14h1v1h-1zM7 14h1v1h-1zM2 15h1v1h-1zM11 15h1v1h-1z'/><path fill='%235f5f5f' d='M15 0h1v1h-1zM5 1h1v1h-1zM10 1h1v1h-1zM2 3h1v1h-1zM8 7h1v1h-1zM10 8h1v1h-1zM9 9h2v1h-2zM14 9h1v1h-1zM4 12h1v1h-1zM13 12h1v1h-1zM3 15h1v1h-1zM5 15h1v1h-1zM10 15h1v1h-1zM13 15h1v1h-1z'/></svg>");
  --mc-grass:url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' shape-rendering='crispEdges'><path fill='%235b9b35' d='M0 0h1v1h-1zM2 0h2v1h-2zM10 0h1v1h-1zM12 0h3v1h-3zM6 1h1v1h-1zM9 1h1v1h-1zM11 1h2v1h-2zM1 2h2v1h-2zM10 2h2v1h-2zM13 2h2v1h-2zM2 3h2v1h-2zM5 3h1v1h-1zM10 3h1v1h-1zM13 3h2v1h-2zM0 4h1v1h-1z'/><path fill='%234c8a2e' d='M1 0h1v1h-1zM6 0h1v1h-1zM11 0h1v1h-1zM0 1h2v1h-2zM10 1h1v1h-1zM13 1h1v1h-1zM3 2h1v1h-1zM6 2h1v1h-1zM12 3h1v1h-1zM2 4h1v1h-1z'/><path fill='%237cbd48' d='M4 0h1v1h-1zM9 0h1v1h-1zM14 1h1v1h-1zM9 2h1v1h-1zM12 2h1v1h-1zM15 2h1v1h-1zM0 3h1v1h-1zM7 4h1v1h-1zM14 4h1v1h-1z'/><path fill='%236aab3d' d='M5 0h1v1h-1zM7 0h2v1h-2zM15 0h1v1h-1zM2 1h4v1h-4zM7 1h2v1h-2zM15 1h1v1h-1zM0 2h1v1h-1zM4 2h2v1h-2zM7 2h2v1h-2zM1 3h1v1h-1zM7 3h2v1h-2zM11 3h1v1h-1zM11 4h1v1h-1z'/><path fill='%2379553a' d='M4 3h1v1h-1zM6 3h1v1h-1zM9 3h1v1h-1zM1 4h1v1h-1zM5 4h1v1h-1zM10 4h1v1h-1zM12 4h1v1h-1zM2 5h1v1h-1zM6 5h1v1h-1zM8 5h3v1h-3zM12 5h2v1h-2zM4 6h2v1h-2zM8 6h3v1h-3zM14 6h1v1h-1zM1 7h1v1h-1zM7 7h1v1h-1zM12 7h1v1h-1zM15 7h1v1h-1zM1 8h5v1h-5zM8 8h1v1h-1zM13 8h1v1h-1zM0 9h2v1h-2zM3 9h1v1h-1zM5 9h1v1h-1zM7 9h2v1h-2zM11 9h2v1h-2zM2 10h1v1h-1zM4 10h1v1h-1zM7 10h1v1h-1zM9 10h1v1h-1zM11 10h1v1h-1zM13 10h2v1h-2zM0 11h1v1h-1zM11 11h2v1h-2zM0 12h1v1h-1zM2 12h2v1h-2zM7 12h1v1h-1zM0 13h1v1h-1zM2 13h2v1h-2zM6 13h1v1h-1zM11 13h2v1h-2zM14 13h1v1h-1zM5 14h1v1h-1zM9 14h2v1h-2zM12 14h1v1h-1zM0 15h2v1h-2zM3 15h1v1h-1zM8 15h1v1h-1zM15 15h1v1h-1z'/><path fill='%239b6e4b' d='M15 3h1v1h-1zM3 4h1v1h-1zM8 4h2v1h-2zM15 4h1v1h-1zM7 6h1v1h-1zM15 6h1v1h-1zM3 7h1v1h-1zM10 7h1v1h-1zM14 7h1v1h-1zM4 9h1v1h-1zM6 9h1v1h-1zM14 9h2v1h-2zM3 10h1v1h-1zM5 10h1v1h-1zM10 10h1v1h-1zM15 10h1v1h-1zM3 11h1v1h-1zM5 11h1v1h-1zM9 11h2v1h-2zM14 11h1v1h-1zM1 12h1v1h-1zM9 12h3v1h-3zM14 12h1v1h-1zM4 13h1v1h-1zM7 13h1v1h-1zM13 13h1v1h-1zM15 13h1v1h-1zM0 14h1v1h-1zM2 14h1v1h-1zM7 14h1v1h-1zM15 14h1v1h-1zM4 15h1v1h-1zM7 15h1v1h-1zM12 15h1v1h-1zM14 15h1v1h-1z'/><path fill='%23866043' d='M4 4h1v1h-1zM6 4h1v1h-1zM13 4h1v1h-1zM0 5h2v1h-2zM3 5h3v1h-3zM7 5h1v1h-1zM11 5h1v1h-1zM15 5h1v1h-1zM0 6h2v1h-2zM3 6h1v1h-1zM11 6h1v1h-1zM13 6h1v1h-1zM2 7h1v1h-1zM4 7h3v1h-3zM9 7h1v1h-1zM11 7h1v1h-1zM13 7h1v1h-1zM0 8h1v1h-1zM7 8h1v1h-1zM9 8h2v1h-2zM12 8h1v1h-1zM14 8h2v1h-2zM2 9h1v1h-1zM9 9h1v1h-1zM13 9h1v1h-1zM1 10h1v1h-1zM6 10h1v1h-1zM12 10h1v1h-1zM1 11h2v1h-2zM4 11h1v1h-1zM6 11h3v1h-3zM13 11h1v1h-1zM15 11h1v1h-1zM6 12h1v1h-1zM8 12h1v1h-1zM12 12h2v1h-2zM15 12h1v1h-1zM1 13h1v1h-1zM5 13h1v1h-1zM8 13h1v1h-1zM1 14h1v1h-1zM3 14h2v1h-2zM6 14h1v1h-1zM8 14h1v1h-1zM11 14h1v1h-1zM13 14h1v1h-1zM2 15h1v1h-1zM5 15h2v1h-2zM9 15h3v1h-3z'/><path fill='%23593d29' d='M14 5h1v1h-1zM2 6h1v1h-1zM6 6h1v1h-1zM12 6h1v1h-1zM0 7h1v1h-1zM8 7h1v1h-1zM6 8h1v1h-1zM11 8h1v1h-1zM10 9h1v1h-1zM0 10h1v1h-1zM8 10h1v1h-1zM4 12h2v1h-2zM9 13h2v1h-2zM14 14h1v1h-1zM13 15h1v1h-1z'/></svg>");
  --mc-planks:url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' shape-rendering='crispEdges'><path fill='%23a8864f' d='M0 0h2v1h-2zM4 0h3v1h-3zM10 0h1v1h-1zM12 0h1v1h-1zM14 0h1v1h-1zM3 1h1v1h-1zM6 1h2v1h-2zM10 1h1v1h-1zM12 1h1v1h-1zM3 2h2v1h-2zM9 2h1v1h-1zM13 2h1v1h-1zM0 3h1v1h-1zM2 3h1v1h-1zM5 3h1v1h-1zM7 3h3v1h-3zM11 3h1v1h-1zM0 4h1v1h-1zM3 4h1v1h-1zM7 4h2v1h-2zM14 4h2v1h-2zM1 5h1v1h-1zM3 5h1v1h-1zM10 5h3v1h-3zM14 5h1v1h-1zM2 6h3v1h-3zM8 6h1v1h-1zM10 6h1v1h-1zM14 6h1v1h-1zM2 7h2v1h-2zM6 7h1v1h-1zM12 7h2v1h-2zM1 8h1v1h-1zM3 8h2v1h-2zM7 8h1v1h-1zM10 8h3v1h-3zM14 8h2v1h-2zM0 9h1v1h-1zM3 9h2v1h-2zM9 9h1v1h-1zM11 9h1v1h-1zM14 9h2v1h-2zM3 10h1v1h-1zM7 10h1v1h-1zM9 10h2v1h-2zM12 10h1v1h-1zM14 10h1v1h-1zM1 11h1v1h-1zM3 11h1v1h-1zM6 11h2v1h-2zM11 11h1v1h-1zM13 11h2v1h-2zM0 12h1v1h-1zM4 12h1v1h-1zM10 12h1v1h-1zM0 13h1v1h-1zM4 13h1v1h-1zM6 13h2v1h-2zM9 13h1v1h-1zM11 13h1v1h-1zM13 13h1v1h-1zM1 14h1v1h-1zM3 14h2v1h-2zM7 14h3v1h-3zM8 15h1v1h-1zM11 15h1v1h-1zM15 15h1v1h-1z'/><path fill='%23b69257' d='M2 0h1v1h-1zM15 0h1v1h-1zM1 1h1v1h-1zM4 1h1v1h-1zM8 1h1v1h-1zM11 1h1v1h-1zM15 1h1v1h-1zM1 2h1v1h-1zM5 2h1v1h-1zM11 2h1v1h-1zM14 2h1v1h-1zM1 3h1v1h-1zM12 3h1v1h-1zM15 3h1v1h-1zM11 4h2v1h-2zM2 5h1v1h-1zM13 5h1v1h-1zM0 6h1v1h-1zM5 7h1v1h-1zM15 7h1v1h-1zM2 8h1v1h-1zM6 8h1v1h-1zM8 9h1v1h-1zM2 10h1v1h-1zM4 10h1v1h-1zM6 10h1v1h-1zM13 10h1v1h-1zM8 11h1v1h-1zM15 12h1v1h-1zM14 13h2v1h-2zM2 15h1v1h-1zM5 15h1v1h-1zM10 15h1v1h-1zM13 15h2v1h-2z'/><path fill='%239c7b47' d='M3 0h1v1h-1zM9 0h1v1h-1zM11 0h1v1h-1zM13 0h1v1h-1zM0 1h1v1h-1zM5 1h1v1h-1zM9 1h1v1h-1zM13 1h1v1h-1zM0 2h1v1h-1zM7 2h1v1h-1zM10 2h1v1h-1zM12 2h1v1h-1zM15 2h1v1h-1zM3 3h2v1h-2zM6 3h1v1h-1zM13 3h2v1h-2zM1 4h1v1h-1zM4 4h3v1h-3zM9 4h1v1h-1zM0 5h1v1h-1zM4 5h4v1h-4zM9 5h1v1h-1zM5 6h1v1h-1zM9 6h1v1h-1zM11 6h3v1h-3zM15 6h1v1h-1zM0 7h2v1h-2zM4 7h1v1h-1zM7 7h4v1h-4zM14 7h1v1h-1zM0 8h1v1h-1zM5 8h1v1h-1zM8 8h2v1h-2zM13 8h1v1h-1zM1 9h2v1h-2zM5 9h1v1h-1zM7 9h1v1h-1zM10 9h1v1h-1zM12 9h2v1h-2zM1 10h1v1h-1zM11 10h1v1h-1zM15 10h1v1h-1zM2 11h1v1h-1zM4 11h2v1h-2zM9 11h1v1h-1zM12 11h1v1h-1zM1 12h3v1h-3zM5 12h3v1h-3zM9 12h1v1h-1zM11 12h4v1h-4zM1 13h3v1h-3zM5 13h1v1h-1zM8 13h1v1h-1zM10 13h1v1h-1zM12 13h1v1h-1zM0 14h1v1h-1zM2 14h1v1h-1zM5 14h2v1h-2zM10 14h4v1h-4zM0 15h2v1h-2zM3 15h2v1h-2zM7 15h1v1h-1zM9 15h1v1h-1zM12 15h1v1h-1z'/><path fill='%238f6d3f' d='M7 0h2v1h-2zM2 1h1v1h-1zM14 1h1v1h-1zM2 2h1v1h-1zM6 2h1v1h-1zM8 2h1v1h-1zM10 3h1v1h-1zM2 4h1v1h-1zM10 4h1v1h-1zM13 4h1v1h-1zM8 5h1v1h-1zM15 5h1v1h-1zM1 6h1v1h-1zM6 6h2v1h-2zM11 7h1v1h-1zM6 9h1v1h-1zM0 10h1v1h-1zM5 10h1v1h-1zM8 10h1v1h-1zM0 11h1v1h-1zM10 11h1v1h-1zM15 11h1v1h-1zM8 12h1v1h-1zM14 14h2v1h-2zM6 15h1v1h-1z'/></svg>");
  --mc-font: "MC Pixel", ui-monospace, monospace;
  --mc-text: #ffffff;
  --mc-subtle: #c8c8c8;
  --mc-dim: #a0a0a0;
  --mc-yellow: #ffff55;
  --mc-gold: #ffaa00;
  --mc-green: #55ff55;
  --mc-aqua: #55ffff;
  --mc-shadow: 2px 2px 0 #3f3f3f;
  --mc-button: #6f6f6f;
  --mc-button-hover: #8f8f8f;
  --mc-panel: #c6c6c6;
  --mc-panel-text: #3f3f3f;
  min-height: 100vh;
  overflow-x: clip;
  background-color: #1e140d;
  background-image: linear-gradient(rgba(0, 0, 0, 0.72), rgba(0, 0, 0, 0.72)), var(--mc-dirt);
  background-size: auto, 64px 64px;
  color: var(--mc-text);
  font-family: var(--mc-font);
  font-size: 17px;
  letter-spacing: 0.02em;
  text-shadow: var(--mc-shadow);
}

[data-modl="page"] *,
[data-modl="page"] *::before,
[data-modl="page"] *::after {
  border-radius: 0 !important;
}

[data-modl="page"] :is(h1, h2, h3, p, span, a, button, input, textarea, select, label, li) {
  font-family: var(--mc-font);
}

[data-modl="page"][data-modl-page="home"] [data-modl="hero"] {
  position: relative;
  margin-left: calc(50% - 50vw);
  margin-right: calc(50% - 50vw);
  padding: 7rem calc(50vw - 50%) 6.5rem;
  background:
    var(--mc-grass) repeat-x left bottom / 64px 64px,
    linear-gradient(180deg, #5c8ee8 0%, #8fb6ff 55%, #b7d1ff 100%);
  border-bottom: 4px solid #000;
  overflow: hidden;
}

[data-modl="page"][data-modl-page="home"] [data-modl="hero"]::before,
[data-modl="page"][data-modl-page="home"] [data-modl="hero"]::after {
  content: "";
  position: absolute;
  left: 0;
  width: 200%;
  z-index: 0;
  pointer-events: none;
  animation: mc-clouds 240s linear infinite;
}

[data-modl="page"][data-modl-page="home"] [data-modl="hero"]::before {
  top: 2.5rem;
  height: 56px;
  background:
    linear-gradient(#ffffff, #ffffff) 4% 0 / 224px 40px no-repeat,
    linear-gradient(#e4e8f0, #e4e8f0) 4% 40px / 224px 16px no-repeat,
    linear-gradient(#ffffff, #ffffff) calc(4% + 64px) 0 / 96px 56px no-repeat,
    linear-gradient(#ffffff, #ffffff) 31% 8px / 160px 32px no-repeat,
    linear-gradient(#e4e8f0, #e4e8f0) 31% 40px / 160px 12px no-repeat,
    linear-gradient(#ffffff, #ffffff) 56% 0 / 288px 40px no-repeat,
    linear-gradient(#e4e8f0, #e4e8f0) 56% 40px / 288px 16px no-repeat,
    linear-gradient(#ffffff, #ffffff) 83% 12px / 192px 28px no-repeat,
    linear-gradient(#e4e8f0, #e4e8f0) 83% 40px / 192px 12px no-repeat;
  opacity: 0.92;
}

[data-modl="page"][data-modl-page="home"] [data-modl="hero"]::after {
  top: 9rem;
  height: 40px;
  background:
    linear-gradient(#ffffff, #ffffff) 18% 0 / 128px 28px no-repeat,
    linear-gradient(#e4e8f0, #e4e8f0) 18% 28px / 128px 10px no-repeat,
    linear-gradient(#ffffff, #ffffff) 68% 4px / 176px 24px no-repeat,
    linear-gradient(#e4e8f0, #e4e8f0) 68% 28px / 176px 10px no-repeat,
    linear-gradient(#ffffff, #ffffff) 96% 0 / 112px 28px no-repeat;
  opacity: 0.6;
  animation-duration: 360s;
}

@keyframes mc-clouds {
  from { transform: translateX(0); }
  to { transform: translateX(-50%); }
}

[data-modl="hero"] > * {
  position: relative;
  z-index: 1;
}

[data-modl="server-name"] {
  font-weight: 700;
  font-size: clamp(2.75rem, 8.5vw, 6rem);
  line-height: 1.1;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: transparent;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.28) 0%, rgba(255, 255, 255, 0.08) 45%, rgba(0, 0, 0, 0.18) 55%, rgba(0, 0, 0, 0.3) 100%),
    var(--mc-stone);
  background-size: 100% 100%, 0.5em 0.5em;
  image-rendering: pixelated;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-stroke: 0;
  text-shadow: none;
  filter:
    drop-shadow(3px 0 0 #1b1b1b)
    drop-shadow(-3px 0 0 #1b1b1b)
    drop-shadow(0 3px 0 #1b1b1b)
    drop-shadow(0 -3px 0 #1b1b1b)
    drop-shadow(0 3px 0 #5a5a5a)
    drop-shadow(0 3px 0 #474747)
    drop-shadow(0 3px 0 #1b1b1b)
    drop-shadow(5px 8px 0 rgba(0, 0, 0, 0.3));
}

[data-modl="tagline"] {
  display: inline-block;
  margin-top: 1.25rem;
  color: var(--mc-yellow);
  font-size: 1.25rem;
  text-shadow: 2px 2px 0 #3f3f15;
  transform: rotate(-8deg);
  animation: mc-splash 0.55s ease-in-out infinite alternate;
}

@keyframes mc-splash {
  from { transform: rotate(-8deg) scale(1); }
  to { transform: rotate(-8deg) scale(1.07); }
}

[data-modl="logo"] img {
  image-rendering: pixelated;
  filter: drop-shadow(4px 4px 0 rgba(0, 0, 0, 0.55));
}

[data-modl="server-address"] {
  background: #000;
  color: var(--mc-text);
  border: 2px solid var(--mc-dim);
  box-shadow: none;
  backdrop-filter: none;
  text-shadow: var(--mc-shadow);
}

[data-modl="server-address"]:hover {
  border-color: var(--mc-text);
}

[data-modl="search"] {
  background: #585858;
  color: #ffffff;
  border: 2px solid #1e1e1e;
  box-shadow: inset 2px 2px 0 #3c3c3c;
  backdrop-filter: none;
  text-shadow: 1px 1px 0 #2e2e2e;
}

[data-modl="search"]:focus {
  border-color: #ffffff;
  outline: none;
}

[data-modl="search"]::placeholder {
  color: #b4b4b4;
}

[data-modl="player-count"] {
  gap: 8px;
  padding: 0;
  background: none;
  color: var(--mc-text);
  text-shadow: var(--mc-shadow);
}

[data-modl="player-count"] > span:first-child {
  display: none;
}

[data-modl="player-count"]::before {
  content: "";
  width: 20px;
  height: 14px;
  background:
    linear-gradient(var(--mc-green), var(--mc-green)) 0 10px / 3px 4px no-repeat,
    linear-gradient(var(--mc-green), var(--mc-green)) 4px 7px / 3px 7px no-repeat,
    linear-gradient(var(--mc-green), var(--mc-green)) 8px 4px / 3px 10px no-repeat,
    linear-gradient(var(--mc-green), var(--mc-green)) 12px 2px / 3px 12px no-repeat,
    linear-gradient(var(--mc-green), var(--mc-green)) 16px 0 / 3px 14px no-repeat;
}

[data-modl="search-results"] {
  background: rgba(16, 0, 16, 0.94);
  color: var(--mc-text);
  border: 2px solid #2d0a63;
  box-shadow: inset 0 0 0 2px #5000ff66;
}

[data-modl="card-grid"] {
  gap: 10px;
}

[data-modl="card"] {
  background: var(--mc-button);
  border: 2px solid #000;
  box-shadow: inset 2px 2px 0 #a8a8a8, inset -2px -3px 0 #4a4a4a;
}

[data-modl="card"]:hover {
  background: var(--mc-button-hover);
  border-color: #ffffff;
  box-shadow: inset 2px 2px 0 #c0c0c0, inset -2px -3px 0 #5e5e5e;
}

[data-modl="card-button"] {
  background: transparent;
  color: var(--mc-text);
  text-shadow: var(--mc-shadow);
}

[data-modl="card-button"] > :has(> [data-modl="card-icon"]) {
  width: 44px;
  height: 44px;
  background: #8b8b8b;
  box-shadow: inset 2px 2px 0 #373737, inset -2px -2px 0 #ffffff;
}

[data-modl="card-icon"] {
  color: #ffffff;
  filter: drop-shadow(2px 2px 0 #3f3f3f);
}

[data-modl="card-title"] {
  color: var(--mc-text);
  font-size: 1.05rem;
}

[data-modl="card-button"]:hover [data-modl="card-title"] {
  color: var(--mc-yellow);
}

[data-modl="card-description"] {
  color: #e0e0e0;
}

[data-modl="category-articles"] {
  background: rgba(0, 0, 0, 0.35);
  border-top: 2px solid #000;
}

[data-modl="article-link"] {
  color: var(--mc-aqua);
}

[data-modl="article-link"]:hover {
  color: var(--mc-yellow);
}

[data-modl="staff-link"],
[data-modl="theme-toggle"] {
  background: var(--mc-button);
  color: var(--mc-text);
  text-shadow: var(--mc-shadow);
  border: 2px solid #000;
  box-shadow: inset 2px 2px 0 #a8a8a8, inset -2px -3px 0 #4a4a4a;
}

[data-modl="staff-link"]:hover,
[data-modl="theme-toggle"]:hover {
  background: var(--mc-button-hover);
  color: var(--mc-yellow);
  border-color: #ffffff;
}

[data-modl="page-title"],
[data-modl="form-title"],
[data-modl="article-title"],
[data-modl="kb-category-title"] {
  font-weight: 700;
  color: var(--mc-text);
  text-shadow: 3px 3px 0 #3f3f3f;
}

[data-modl="form-card"],
[data-modl="ticket"],
[data-modl="article"],
[data-modl="kb-category"],
[data-modl="appeal-lookup"],
[data-modl="ticket-verification"] {
  background: var(--mc-panel);
  color: var(--mc-panel-text);
  text-shadow: none;
  border: 2px solid #000;
  box-shadow: inset 2px 2px 0 #ffffff, inset -2px -2px 0 #555555, 6px 6px 0 rgba(0, 0, 0, 0.45);
}

:is([data-modl="form-card"], [data-modl="ticket"], [data-modl="article"], [data-modl="kb-category"], [data-modl="appeal-lookup"], [data-modl="ticket-verification"]) :is([data-modl="form-title"], [data-modl="article-title"], [data-modl="kb-category-title"], [data-modl="kb-category-description"], [data-modl="article-updated"], label) {
  color: var(--mc-panel-text);
  text-shadow: none;
}

[data-modl="form-field"] :is(input, textarea),
[data-modl="reply"] textarea,
[data-modl="appeal-lookup"] input,
[data-modl="ticket-verification"] input {
  background: #585858;
  color: #ffffff;
  border: 2px solid #1e1e1e;
  box-shadow: inset 2px 2px 0 #3c3c3c;
  text-shadow: 1px 1px 0 #2e2e2e;
}

[data-modl="form-field"] :is(input, textarea)::placeholder,
[data-modl="reply"] textarea::placeholder,
[data-modl="appeal-lookup"] input::placeholder,
[data-modl="ticket-verification"] input::placeholder {
  color: #b4b4b4;
}

[data-modl="form-field"] :is(input, textarea):focus,
[data-modl="reply"] textarea:focus,
[data-modl="appeal-lookup"] input:focus,
[data-modl="ticket-verification"] input:focus {
  border-color: #ffffff;
  outline: none;
}

[data-modl="form-field"] :is(select, button[role="combobox"]),
[data-modl="submit-button"] {
  background: #d0d1d4;
  color: #3f3f3f;
  text-shadow: none;
  border: 2px solid #1e1e1e;
  box-shadow: inset 2px 2px 0 #ffffff, inset 0 -4px 0 #8c8d90;
}

[data-modl="form-field"] :is(select, button[role="combobox"]):hover,
[data-modl="submit-button"]:hover {
  background: #e8e9ec;
  color: #3f3f3f;
  border-color: #ffffff;
}

[data-modl="form-field"] button[role="combobox"] svg {
  color: #3f3f3f;
  opacity: 1;
}

[data-modl="form-field"] button[role="checkbox"] {
  width: 20px;
  height: 20px;
  background: #585858;
  border: 2px solid #1e1e1e;
  box-shadow: inset 2px 2px 0 #3c3c3c;
  color: #ffffff;
}

[data-modl="form-field"] button[role="checkbox"][data-state="checked"] {
  background: #3c8527;
  box-shadow: inset 2px 2px 0 #4f9f38, inset -2px -2px 0 #2a5e1b;
}

[data-modl="form-field"] p {
  color: #5a5a5a;
  text-shadow: none;
}

[data-modl="form-section"] {
  border-color: #8b8b8b;
}

[data-modl="article-body"] a {
  color: #2a2ab0;
}

[data-modl="message-list"] {
  background: rgba(0, 0, 0, 0.5);
  border: 2px solid #000;
  padding: 10px;
}

[data-modl="message"] {
  background: transparent;
  color: var(--mc-text);
  text-shadow: var(--mc-shadow);
  border: none;
  box-shadow: none;
}

[data-modl="message"][data-modl-sender="staff"] {
  border-left: 4px solid var(--mc-gold);
}

[data-modl="message"][data-modl-sender="player"] {
  border-left: 4px solid var(--mc-aqua);
}

[data-modl="message"][data-modl-sender="system"] {
  color: var(--mc-dim);
  font-style: italic;
  border-left: 4px solid #555555;
}

[data-modl="evidence-dropzone"] {
  background: rgba(0, 0, 0, 0.45);
  border: 2px dashed var(--mc-dim);
}

[data-modl="back-link"] {
  color: var(--mc-yellow);
}

[data-modl="footer"] {
  color: var(--mc-dim);
}

[data-modl="footer-link"] {
  color: var(--mc-aqua);
}

[data-modl="footer-link"]:hover {
  color: var(--mc-text);
}

@media (prefers-reduced-motion: reduce) {
  [data-modl="tagline"],
  [data-modl="hero"]::before,
  [data-modl="hero"]::after {
    animation: none !important;
  }
}
```

</details>

## Styling hooks

Select elements only by these attributes. Class names and markup change between releases; the hooks are contract version 1: new hooks can arrive in any release, and a hook is renamed or removed only in a new major version, named in its release notes.

The `page` element carries `data-modl-page`: `home`, `knowledgebase`, `article`, `appeal`, `submit-ticket`, `ticket`, or `upload-evidence`. Every `message` carries `data-modl-sender`: `player`, `staff`, or `system`.

| Hook (`data-modl`) | Element | Pages |
|---|---|---|
| `page` | page root | all |
| `footer` | source code link; on the homepage also the server name and footer links, when footer links are set | all |
| `page-title` | main heading | knowledgebase, appeal, ticket, upload-evidence |
| `home-actions` | theme toggle and staff button | home |
| `theme-toggle` | light and dark toggle | home |
| `staff-link` | staff sign-in or panel button | home |
| `hero` | logo, server name, tagline, address, player count, and search | home |
| `logo` | logo box, shown when a homepage icon is set | home |
| `server-name` | server name heading | home |
| `tagline` | tagline | home |
| `server-address` | copyable server address | home |
| `player-count` | players online | home |
| `search` | search input | home, knowledgebase |
| `search-results` | search results | home |
| `card-grid` | card rows | home |
| `card` | one card row | home |
| `card-icon` | card icon | home |
| `card-title` | card title | home |
| `card-description` | card text | home |
| `card-button` | the clickable row (link or the toggle of a category) | home |
| `category-articles` | opened category inside its row | home |
| `footer-name` | server name in the footer (shown only with footer links) | home |
| `footer-link` | one footer link | home |
| `article-list` | list of article links | home, knowledgebase |
| `article-link` | link to an article | home, knowledgebase |
| `kb-category` | category section | knowledgebase |
| `kb-category-title` | category title | knowledgebase |
| `kb-category-description` | category text | knowledgebase |
| `back-link` | link back to the homepage | article |
| `article` | article | article |
| `article-title` | article title | article |
| `article-updated` | last updated date | article |
| `article-body` | article text | article |
| `ticket-types` | ticket type picker | submit-ticket |
| `ticket-type` | one ticket type | submit-ticket |
| `form-card` | card around a form | submit-ticket, appeal, ticket |
| `form-title` | form title | submit-ticket, appeal, ticket |
| `form` | form | submit-ticket, appeal, ticket |
| `form-section` | form section | submit-ticket, appeal, ticket |
| `form-field` | one field with its label | submit-ticket, appeal, ticket |
| `submit-button` | main button of a form or reply | submit-ticket, appeal, ticket, upload-evidence |
| `appeal-lookup` | punishment ID lookup | appeal |
| `appeal-punishment` | punishment details | appeal |
| `appeal-status` | appeal status and conversation | appeal |
| `ticket-verification` | email verification | ticket |
| `ticket` | ticket conversation | ticket |
| `ticket-header` | ticket subject and details | ticket |
| `message-list` | conversation | ticket, appeal |
| `message` | one message | ticket, appeal |
| `reply` | reply box | ticket, appeal |
| `evidence-dropzone` | file drop area | upload-evidence |
| `evidence-files` | uploaded files | upload-evidence |

```css
[data-modl-page="home"] [data-modl="card"] {
  border-width: 2px;
}

[data-modl="message"][data-modl-sender="staff"] {
  background: hsl(var(--primary) / 0.06);
}
```
