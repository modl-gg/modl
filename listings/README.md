# Marketplace listings

The text of modl's pages on plugin marketplaces. Edit here first, then paste into each site, so every page says the same thing.

| File | Format | Paste into |
|---|---|---|
| `summary.txt` | plain text | The short summary or tag line field of every site (line 1, at most 100 characters), and the Modrinth summary (line 2, at most 256 characters) |
| `description.md` | Markdown | Modrinth, Hangar (PaperMC), GitHub release notes intro |
| `description.bbcode` | BBCode | SpigotMC, BuiltByBit, Polymart |

Images live in `images/` and are linked from the descriptions as `https://raw.githubusercontent.com/modl-gg/modl/main/listings/images/<file>`, so marketplace pages keep working after `cdn.modl.gg` is gone. Never rename or delete a file there: published pages link it. Add a new file instead, and keep GIFs around 3 MB.

`description.md` and `description.bbcode` carry the same content. Change both in the same commit, and check them against the wiki when a feature, a requirement or a supported platform changes.
