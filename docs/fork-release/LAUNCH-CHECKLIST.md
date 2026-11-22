# Fork release launch checklist (v1.3.0-fork.1)

## 1. Signing key custody — DECISION + BACKUP
- Keystore: `~/.config/wisp-fork/wisp-fork-release.jks` (PKCS12, RSA-4096,
  alias `wisp-fork`, validity 30y). Secrets in
  `~/.config/wisp-fork/signing.env` (mode 600).
- The key password equals the keystore password (PKCS12).
- **Back the .jks up off this machine now.** Losing it permanently locks
  out updates for every installed device (Android refuses updates signed
  by a different key).
- Never commit it; in CI it lives only in the base64 secret.

## 2. CI release path
- Workflow: `.github/workflows/release-fork.yml` (tag `v*fork*` or manual).
- One-time secret setup:
  ```
  gh secret set WISP_FORK_KEYSTORE_BASE64 < <(base64 -w0 ~/.config/wisp-fork/wisp-fork-release.jks)
  source ~/.config/wisp-fork/signing.env
  gh secret set WISP_FORK_KEYSTORE_PASSWORD <<< "$WISP_FORK_KEYSTORE_PASSWORD"
  gh secret set WISP_FORK_KEY_ALIAS <<< "$WISP_FORK_KEY_ALIAS"
  gh secret set WISP_FORK_KEY_PASSWORD <<< "$WISP_FORK_KEY_PASSWORD"
  ```
- Release: push tag `v1.3.0-fork.1` (must contain "fork" to match the
  trigger), or run the workflow manually.

## 3. BLOCKER — BREEZ_API_KEY (decide before publishing)
- `local.properties` on this machine has no `breez.api.key`, so the
  current APK embeds an **empty** `BREEZ_API_KEY` BuildConfig field and
  the Spark/Lightning wallet features are expected to be non-functional.
- Options: (a) obtain a key, put it in `local.properties`, rebuild —
  note it will be extractable from the APK (same as upstream's model);
  (b) publish without wallet support and say so in the notes; (c) defer
  the release until (a). Recommend (a).

## 4. Same application ID caveat
- `com.wisp.app`, versionCode 92: installing over the upstream build
  replaces it (data preserved) but cannot be undone without uninstalling.
  Backup the recovery phrase before switching. This is also stated on
  the download page.

## 5. Distribution hosting — DECISION
- `dist/index.html` links to `https://wisp.rtard.de/download/…`; the
  actual bytes can be served either by a static host (rsync, see
  `dist/publish.sh`) or GitHub Release assets (workflow uploads them).
  Whoever controls wisp.rtard.de DNS/hosting must confirm before the
  links go live; otherwise point the links at the GitHub release URL.

## 6. Zapstore
- `zapstore.yaml` is a DRAFT: confirm field names against Zapstore's
  current spec, register the release signing certificate with Zapstore,
  and confirm the APK URL resolves. Do not submit before §3 and §5.

## 7. Pre-launch verification (local, already done once)
- `apksigner verify --print-certs` → CN=Wisp Fork Release, cert SHA-256
  `3b99014bdceabeaa0575d8c6aac39150d6fdde78371140384789e0eb3590256a`.
- versionCode 92, versionName 1.3.0-fork.1, applicationId com.wisp.app.
- SHA-256 of the built APK recorded in `dist/release.json`.
- Still to do: install on a device/emulator, send/receive a DM, run a
  zap end-to-end once the API key decision is made.

## 8. Relation to upstream
- Base: barrydeen/wisp `main` @ 09e26e0e (all merged security PRs).
- Carries open PRs as commits: #675 (strict 2-file version Barry asked
  for, cherry-pick of 8389f871) and the #690 mnemonic stack.
- The cap/SSRF/checksum work already landed upstream via #687 — nothing
  was dropped. If Barry merges #675/#690, the next fork tag can be cut
  from plain `main`.
- Keep the release branch `release/v1.3.0-fork.1` on
  github.com/mptfire/wisp so every published binary maps to public
  source.

## 9. Reproducibility
- Builds pinned to Temurin JDK 17 (CI) — local `~/.gradle/gradle.properties`
  pins `org.gradle.java.home` to Temurin 17 because Fedora's Java 25
  (25.0.4.1) breaks the Kotlin compiler's version parser.
- R8 output is not bit-identical across environments: verify the
  **signing certificate**, not only the file hash, when in doubt.
- This page/manifest contains no analytics and makes no network calls.
