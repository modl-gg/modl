# Customizing the Public Pages

The public pages are the homepage, the knowledgebase and its articles, the ticket and appeal forms, the player ticket view and evidence upload. The staff panel, sign-in and setup pages always keep the default look.

## Theme

Panel > Settings > Public site > Theme. Viewing needs `admin.settings.view.content`, changing needs `admin.settings.modify.content`. The page previews the light and dark look before you save. Changes are audited.

| Setting | Values | Effect |
|---|---|---|
| Brand color | `#rrggbb`, empty for the default | buttons, links, focus rings and the modl wordmark; text on it turns black or white, whichever reads better |
| Accent color | `#rrggbb`, empty for the default | homepage card icons |
| Background color | `#rrggbb`, empty for the default | page background: a light tint of it in light mode and a dark shade in dark mode, so text stays readable |
| Background image | PNG, JPEG, GIF or WebP, at most 10 MB and the upload limit | fixed layer behind the page (`[data-modl="page"]::before`) under a veil of the background color |
| Font | System (default), Inter, Nunito, Lora, Space Grotesk | bundled with modl, downloaded by visitors only when chosen |
| Corner radius | None, Small, Medium (default), Large | cards, buttons and inputs |
| Default color scheme | System (default), Light, Dark | used until a visitor picks light or dark with the homepage toggle; that choice stays in their browser |

The modl.gg import does not bring a theme or custom CSS.

## Custom CSS

Panel > Settings > Public site > Custom CSS, owner only. At most 64 KiB, audited. modl serves it at `/v1/public/theme.css` (`text/css`, cached per content revision) and loads it only on the public pages, after the theme. It is not layered, so its rules win over the built-in styles and the theme without `!important`.

Saving is refused when the CSS contains:

| Content (also when written with CSS escapes or split by comments) | Error code |
|---|---|
| `@import` | `CUSTOM_CSS_IMPORT` |
| `</style` | `CUSTOM_CSS_MARKUP` |
| `javascript:` or `vbscript:` URLs, `expression(`, `behavior:`, `-moz-binding` | `CUSTOM_CSS_SCRIPT` |
| NUL characters | `CUSTOM_CSS_INVALID_CHARACTER` |
| more than 64 KiB (UTF-8) | `CUSTOM_CSS_TOO_LARGE` |

These checks are a backstop; the Content Security Policy is the boundary, and custom CSS does not change it. Images in `url()` load from this server, the storage backend and `https:` addresses. Fonts load only from this server and `data:` URLs, so web fonts from other sites (such as Google Fonts) are blocked; pick a bundled font in the theme instead.

The theme sets CSS variables that custom CSS can reuse as `hsl(var(--primary))`: `--primary`, `--primary-foreground`, `--accent`, `--background`, `--foreground`, `--card`, `--muted-foreground`, `--border`; plus `--radius` and `--font-sans`.

### Safe mode

Open a public page with `?nocss` at the end of its address (for example `https://panel.example.com/?nocss`) to see it without custom CSS until the tab is reloaded without it. The staff panel never loads custom CSS, so the editor always works.

## Styling hooks

Select elements only by these attributes. Class names and markup change between releases; the hooks are contract version 1: new hooks can arrive in any release, and a hook is renamed or removed only in a new major version, named in its release notes.

The `page` element carries `data-modl-page`: `home`, `knowledgebase`, `article`, `appeal`, `submit-ticket`, `ticket` or `upload-evidence`. Every `message` carries `data-modl-sender`: `player`, `staff` or `system`.

| Hook (`data-modl`) | Element | Pages |
|---|---|---|
| `page` | page root | all |
| `footer` | source code link | all |
| `page-title` | main heading | knowledgebase, appeal, ticket, upload-evidence |
| `home-actions` | theme toggle and staff button | home |
| `theme-toggle` | light and dark toggle | home |
| `staff-link` | staff sign-in or panel button | home |
| `hero` | logo, search and cards | home |
| `logo` | logo box | home |
| `search` | search input | home, knowledgebase |
| `search-results` | search results | home |
| `card-grid` | card grid | home |
| `card` | homepage card | home |
| `card-icon` | card icon | home |
| `card-title` | card title | home |
| `card-description` | card text | home |
| `card-button` | card button | home |
| `category-articles` | opened category below the cards | home |
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
