# Email and Sign-in

## Email (SMTP)

Optional. Without it, login codes, email change and approval codes and invitation links are printed to the console (Minecraft console, `docker compose logs modl` or the jar's terminal), and players reach tickets and appeals only through the private link they get when they create one.

Set it in Panel > Settings > General > Email, which checks the connection and login before saving and can send a test email. Or set `embedded.smtp` (plugin) or `MODL_SMTP_*` ([Configuration](Configuration)); the panel then shows it read-only. Port 465 uses implicit TLS; other ports use STARTTLS unless `starttls` is false. A failed send falls back to the console.

## Sign-in methods

| Method | Notes |
|---|---|
| Email code (default) | 6 digits, valid 5 minutes, one new code per minute. After 20 wrong codes in 24 hours, no codes are sent to that address for the rest of the window. |
| Email and password | 12 to 128 characters, stored with Argon2id. |
| Passkey | Needs HTTPS or `localhost`; bound to the panel's domain. |
| `/modl panel` in game | One-time link, 2 minutes, for linked accounts. Refused on offline-mode servers without proxy forwarding unless `panel_login.allow_offline_mode: true`. |

The owner picks email code or password for everyone in Settings > Staff > Sign-in; passkeys and `/modl panel` work with either. Sessions last 14 days and can be signed out in Settings > Account > Security.

Changing your email needs a code from your authenticator app, or else your current password, or else a code sent to your current address (printed to the console without SMTP), then a code sent to the new address.

## Two-factor

- Anyone can add an authenticator app (TOTP) in Settings > Account > Security and gets 10 single-use recovery codes.
- With TOTP set up, every sign-in except a passkey asks for a code.
- The owner can require two-factor (authenticator or passkey) for all staff in Settings > Staff > Sign-in; staff without one must add it at their next sign-in.
- The owner can create a 1-hour password reset link for a staff member and reset a staff member's second factor. Neither works on the owner's own account.

## Owner locked out

There is no recovery flow; keep recovery codes and a second sign-in method. Without SMTP, email codes still appear in the console. Otherwise change the owner email (`embedded.admin-email` or `MODL_ADMIN_EMAIL`) and restart; modl prints an owner sign-in code for that email ([First Run](First-Run)). The previous owner loses access unless they are also a staff member.

## In-game staff 2FA

Separate from panel two-factor: with `staff_2fa.enabled: true` in `config.yml`, staff run `/verify` and confirm the link in the panel before staff commands work. A confirmation lasts 7 days for that IP.

Backups contain password hashes and authenticator secrets; protect them like the database.
