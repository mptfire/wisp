## Wisp fork 1.3.0-fork.1 (versionCode 92)

Security-focused fork release of Wisp, based on upstream `main` at
`09e26e0e` plus two open upstream PRs landed here:

- **Google Sign-In removed & sovereign Nostr auth**: stripped Google
  OAuth, Google Drive backup, `googleid`, and `play-services-auth` SDKs,
  and shipped full sovereign Nostr sign-in:
  - **NIP-55 external signer (Amber)** restored for signing and DM
    encryption without exposing your private key to the app.
  - **NIP-49 encrypted key backups (`ncryptsec1…`)**: scrypt +
    XChaCha20-Poly1305 login unlock and encrypted export from both
    onboarding and Settings → Keys (with `log_n` DoS bounds and AAD
    verification).
  - **NIP-06 recovery phrases**: 12/24-word BIP-39 mnemonic login
    (`m/44'/1237'/0'/0/0`) with Unicode whitespace normalization and
    fail-closed checksum validation.
  - **Mandatory signup backup step**: new accounts confirm key backup
    (reveal/copy, password-protected `ncryptsec`, or OS password manager)
    before continuing onboarding, resilient to background process death.
- **DM seal authentication** (upstream #675): NIP-17 rumor authors
  (kinds 14, 15, 7, 1) are authenticated against the kind-13 seal's
  signature and bound to the seal's pubkey before a message is trusted.
- **Fail-closed BIP-39 validation** (upstream #690): recovery-phrase
  checks now validate wordlist membership and checksum, normalize the
  Unicode spaces PDFs and note apps insert, and fail closed if the
  wordlist is missing or truncated.
- Carries all previously merged hardening: LNURL invoice verification,
  zap receipt consistency checks, SSRF guards on LNURL discovery, and
  bech32 checksum validation on BOLT-11 invoices.
- Also lands the `fix/ssrf-and-media-hardening` stack: LNURL bodies
  read correctly on chunked/HTTP-2 responses (zaps to Cloudflare-
  fronted lightning providers were broken), redirect/callback SSRF
  protection, DM media auto-fetch gated behind a setting (no zero-click
  IP leak; 25 MB cap, SHA-256 verification, decompression-bomb guard),
  Bolt11 HRP and expiry-tag guards, and crash-log secret redaction.

### Notes
- Own package ID `de.rtard.wisp`, launcher name "Wisp Fork": installs
  side-by-side with upstream Wisp (`com.wisp.app`). Restore and verify
  your recovery phrase inside Wisp Fork before uninstalling upstream.
- arm64-v8a only, Android 8.0+.
- Verify the APK against `SHA256SUMS`; signing certificate SHA-256:
  `3b99014bdceabeaa0575d8c6aac39150d6fdde78371140384789e0eb3590256a`.
