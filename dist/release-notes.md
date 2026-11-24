## Fire Wisp 1.3.0-fork.2 (versionCode 93)

- **Android Share Target (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`)**:
  - Fire Wisp now registers in Android's native Share sheet for `text/plain`
    (YouTube videos, web links, text), `image/*`, and `video/*` (including
    multi-image shares).
  - Sharing from YouTube, a browser, or Gallery opens Fire Wisp straight to
    the Compose screen with text/links pre-filled and shared photos/videos
    uploaded automatically to your configured Blossom media server.
  - Only `content://` URIs are accepted from external share intents (`file://`
    URIs are rejected to prevent local file exfiltration).

---

## Fire Wisp 1.3.0-fork.1 (versionCode 92)

Security- and privacy-focused Android fork of Wisp, based on upstream `main`
at `09e26e0e` plus sovereign Nostr auth, embedded Tor, and upstream/Dark Wisp
hardening and UX ports:

- **Google Sign-In removed & sovereign Nostr auth**: stripped Google OAuth,
  Google Drive backup, `googleid`, and `play-services-auth` SDKs, and shipped
  full sovereign Nostr sign-in:
  - **NIP-55 external signer (Amber)** restored for signing and DM encryption
    without exposing your private key to the app.
  - **NIP-49 encrypted key backups (`ncryptsec1…`)**: scrypt +
    XChaCha20-Poly1305 login unlock and encrypted export from both onboarding
    and Settings → Keys (with `log_n` DoS bounds and AAD verification).
  - **NIP-06 recovery phrases**: 12/24-word BIP-39 mnemonic login
    (`m/44'/1237'/0'/0/0`) with Unicode whitespace normalization and
    fail-closed checksum validation.
  - **Mandatory signup backup step**: new accounts confirm key backup
    (reveal/copy, password-protected `ncryptsec`, or OS password manager)
    before continuing onboarding, resilient to background process death.
- **Embedded Tor (`kmp-tor 2.4.0`) & compact APK packaging**:
  - Fail-closed Tor routing (`127.0.0.1:9` discard proxy while bootstrapping,
    live client pool eviction on toggle), pre-login corner toggle + drawer
    toggle, `SafeHttp` no-clearnet-DNS-leak mode under Tor, and BUD-10
    `BlossomUriResolver` / `SplashViewModel` routed through `HttpClientFactory`.
  - Compressed native library packaging (`jniLibs.useLegacyPackaging = true`),
    cutting the direct APK download roughly in half.
- **DM seal authentication** (upstream #675): NIP-17 rumor authors (kinds 14,
  15, 7, 1) are authenticated against the kind-13 seal's signature and bound
  to the seal's pubkey before a message is trusted.
- **Fail-closed BIP-39 validation** (upstream #690): validates wordlist
  membership and checksum, normalizes Unicode spaces, and fails closed if the
  wordlist is missing or truncated.
- **SSRF, Lightning & media hardening**: LNURL chunked/HTTP-2 response fix,
  redirect/callback SSRF protection, pre-pay BOLT-11 verification + zap
  receipt consistency checks, DM media auto-fetch gated behind a setting (no
  zero-click IP leak; 25 MB cap, SHA-256 verification, decompression-bomb
  guard), and crash-log secret redaction.
- **Dedicated Fire Wisp relay & free public Blossom defaults**:
  - Prepends `wss://firewisp.relay.rtard.de` to default relays (anti-spam
    `strfry` relay with NIP-13 PoW >= 12 bits on public posts/reactions, kind
    allowlist, mention caps, and per-pubkey rate limits).
  - Defaults media uploads to `https://blossom.nostr.build` with automatic
    fallback to `https://blossom.primal.net` (users can configure any custom or
    private Blossom server in Settings).
- **Dark Wisp UX & protocol ports**:
  - Undo reposts + NIP-09 author-verified deletion + profile scroll stability
    (`#106`).
  - Image alt text via NIP-92 `imeta` (`#92`).
  - Hashtag muting (`t`-tags) with immediate feed re-filtering (`#61`).
  - Blossom BUD-05 `t=media` vs BUD-02 `t=upload` auth separation (`#104`),
    preserving fail-closed SHA-256 descriptor verification.
  - One-line previews in compact notifications (`#105`).

### Notes
- Own package ID `de.rtard.wisp`, launcher name "Fire Wisp": installs
  side-by-side with upstream Wisp (`com.wisp.app`). Restore and verify your
  recovery phrase inside Fire Wisp before uninstalling upstream.
- Both the built-in self-custodial Spark Lightning wallet (Breez SDK) and
  NWC (Nostr Wallet Connect) are enabled.
- arm64-v8a only, Android 8.0+.
- Verify the APK against `SHA256SUMS`; signing certificate SHA-256:
  `3b99014bdceabeaa0575d8c6aac39150d6fdde78371140384789e0eb3590256a`.
