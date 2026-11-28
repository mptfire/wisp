## Fire Wisp 1.3.0-fork.4 (versionCode 95)

- **Zero-Google On-Device Inline Translation (`slimt` + Top-20 Mozilla Models)**:
  - Restored offline in-app note translation without Google Play Services or ML Kit, compiled from open-source `slimt` (GPL-2.0, `ruy` + `sentencepiece` `arm64-v8a` JNI) with SHA-256 verified Top-20 Mozilla Firefox Translations models (MPL-2.0, bidirectional English ↔ 20 languages) self-hosted on `https://firewisp.de/models` with zero access logging.
  - Translating from the post 3-dots menu replaces the note body in-place (`Show Original` flips cleanly back) with Nostr token/URL/hashtag/emoji preservation, a clear download confirmation prompt (stating Tor vs clearnet), an offline language detector + manual source-language picker fallback, and Settings → Interface controls (`Auto-translate`, target language, model download/delete).
- **Tor & Privacy Hardening**:
  - Added fail-closed DNS blocking (`HttpClientFactory.torFailClosedDns`) whenever Tor is `Pending` or `Ready`.
  - Hardened translation model downloads with HTTPS-only, `ConnectionSpec.MODERN_TLS`, no redirects, `HTTP/1.1` with transient retry recovery, mid-stream byte-limit abort, and pinned SHA-256 verification.
  - Routed `ProfileRepository.cacheAvatar()` (with HTTPS-only + 10 MB cap) and `AudioPlayerController` through `HttpClientFactory` so audio playback and avatar caching obey Tor routing.
- **Profile & Gallery Fixes**:
  - Fixed Gallery video tiles (`kind 21`/`22`) rendering black by using `Nip71` video poster thumbnails and restoring the Play badge overlay.
  - Added a 2-second EOSE grace window to profile `loadMoreNotes()` / `loadMoreReplies()` so slower archival relays are not cut off by the fastest empty relay's EOSE.

---

## Fire Wisp 1.3.0-fork.3 (versionCode 94)

- **Native NIP-46 Remote Signer (Bunker) & Unified Signer Picker**:
  - Sign in with any NIP-46 remote bunker via `bunker://` URI (paste or QR scan)
    or client-initiated `nostrconnect://` QR / deep-link pairing (NIP-44 v2
    default with transparent NIP-04 fallback, sender verification, and safe
    `https://` `auth_url` approval handling).
  - Unified external Signer Picker discovers both installed NIP-55 (`nostrsigner:`
    — Amber, Primal, Cambium) and NIP-46 (`nostrconnect:` — Amethyst's built-in
    bunker) apps, allowing 1-tap pairing directly from the sign-in screen.
  - Multi-account encrypted persistence (`SigningMode.BUNKER`), lazy NIP-17 DM
    decryption, Tor-compatible relay routing, and a live ping health check under
    Settings → Keys.
- **Local Relay Support (Citrine & LAN)**:
  - Restored the **Local** tab under Relays with a 1-tap **Citrine** preset
    (`ws://127.0.0.1:4869`), private LAN relay support (`192.168.x.x`,
    `10.x.x.x`, `172.16-31.x.x`, `*.local`), live connection status, error
    reporting, 1-tap **Reconnect**, write policy controls, event kind filters,
    and `"My Local Relay"` in the Feed relay picker.
  - Dedicated host-guarded local WebSocket client bypasses Tor strictly for
    validated loopback/LAN hosts so Citrine works cleanly with Tor on or off
    and never leaks local relays to NIP-65 (`kind: 10002`).
- **Drawer Menu Layout**:
  - Moved the **Set up wallet** / live balance row directly above **Settings**
    in the side navigation drawer.
- **Zero-Google Privacy Hardening (Full ML Kit & Play Services Removal)**:
  - Replaced Google ML Kit barcode scanning (`mlkit:barcode-scanning`,
    `camera-mlkit-vision`) in the camera QR scanner and wallet gallery QR
    importer with pure open-source `zxing-core` (including inverted dark-mode
    QR support and background-thread frame decoding).
  - Removed Google ML Kit translation and language-id (`mlkit:translate`,
    `mlkit:language-id`, `kotlinx-coroutines-play-services`) and hid the
    in-app Translate UI, stripping all remaining `com.google.mlkit`,
    `com.google.android.gms`, `com.google.firebase`, and
    `com.google.android.datatransport` (Clearcut telemetry) SDKs and native
    `.so` blobs from the APK.
  - Hardened Android Credential Manager (`NostrCredentialSaver`): removed
    automatic credential-picker triggering when focusing the login field, and
    added a clear privacy warning before saving an `nsec` to the system
    password manager (noting Google Password Manager is the default on stock
    Android unless KeePassDX/Bitwarden/Proton Pass is configured).
- **Block Confirmation Dialog**:
  - Every Block option across Feed, Thread, Profile, Search, Notifications,
    Bookmarks, Live Chat, and Group Chat now displays a confirmation prompt with
    the user's name before blocking and purging.
- **Canonical Fire Wisp Relay**:
  - Updated default Fire Wisp relay to `wss://relay.firewisp.de` with automatic
    migration for existing accounts still referencing `wss://firewisp.relay.rtard.de`.

---

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
