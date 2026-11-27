# Customizing the Public Pages

The public pages are the homepage, the knowledgebase and its articles, the ticket and appeal forms, the player ticket view, and evidence upload. The staff panel, sign-in, and setup pages always keep the default look.

https://github.com/user-attachments/assets/db97adcc-5b50-4aef-bc37-4eb4ea7f3427

1. Settings > Public site > Homepage: tagline, server address, cards, and footer links.
2. Settings > Public site > Theme: colors, font, and corner radius.
3. Settings > Public site > Custom CSS (owner only) for anything else ([Custom CSS](Custom-CSS)), using the [styling hooks](#styling-hooks).
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

Panel > Settings > Public site > Custom CSS (owner only) changes anything the homepage and theme settings do not. What it can do, what is refused, safe mode, and a complete example are on [Custom CSS](Custom-CSS).

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
