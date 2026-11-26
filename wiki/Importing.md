# Importing

Back up first ([Backups and Restore](Backups-and-Restore)). Imported bans and mutes take effect as they are imported.

## From other plugins

LiteBans, AdvancedBan, LibertyBans, BanManager, BanSystem, vanilla ban lists, and EssentialsX: see [Migrating from Other Plugins](Migrating-from-Other-Plugins).

## From modl.gg

Only on a fresh installation: no punishments, staff, invitations, or articles of your own yet (unchanged starter articles do not count), and setup not completed.

1. Claim the panel ([First Run](First-Run)). In the wizard's Import step choose Import an export from modl.gg.
2. Upload the export zip, or paste the download link from the export email (used once, not stored). Optionally paste its SHA-256 checksum.
3. Review:
   - what will be imported and what changes on the way (for example a renamed role or a permission that no longer exists);
   - old history: Keep old history or Standard retention (required; disk space shown for both);
   - staff accounts to restore: none are selected until you choose (Select all is one click). Selected staff sign in with their old email;
   - you stay the owner whatever the archive says.
4. Confirm and start. The import runs on the server, survives restarts, and can pause (for example on low disk space) and resume. A paused import can be abandoned until staff access is restored; after that, Finish with the imported data ends it and lists the skipped steps. It does not start while uploads move to another storage or the database moves.

Imported: players, punishments, tickets, replays, files, logs, settings, roles, punishment types, ticket forms, quick responses, labels, homepage cards, and the knowledgebase (these replace the defaults). Address entries that are not valid IP addresses are skipped, internal staff-note replies become ticket notes, and old removals and revocations of punishments become pardons. Not imported: API keys, sessions, passkeys, invitations, credentials, the custom domain, the homepage header and footer, the public site theme, and custom CSS. Webhooks and AI are imported switched off. Old public ticket links stop working.

After the import: set your servers' keys ([Proxy Network](Proxy-Network), [Separate Panel](Separate-Panel#connect-servers)), have staff add passkeys again, and review webhooks and AI.

A server with the old modl.gg plugin converts `boot.yml` to `connection.yml` on its first start with the new jar; set its connection as above.
