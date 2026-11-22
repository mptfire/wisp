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
  gh secret set BREEZ_API_KEY <<< "<spark api key>"
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

## 3b. BLOCKER — Google Sign-In / Drive backup broken under the fork key
- `app/src/main/res/values/strings.xml` hardcodes upstream's
  `google_web_client_id` (GCP project 410412439051-...). Play Services
  authorizes by (packageName, signing cert SHA-1); the fork cert
  (SHA-1 e617021fbcba18ce620d2e9aa096b796cdefc4c8) is not registered
  there, so Google Sign-In and Drive backup fail on real devices.
- Options: (a) create fork GCP OAuth Web+Android clients for the fork
  (applicationId + SHA-1) and make `google_web_client_id`
  build-configurable; or (b) document Drive backup / Google sign-in as
  unsupported in the fork. Decide before launch.

## 4. Same application ID caveat — PRODUCT DECISION OPEN
- The fork is signed with its own key. Android will NOT install it over
  the upstream build (`com.wisp.app`, different cert) — the installer
  fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE. Switching requires:
  back up recovery phrase/nsec, uninstall upstream Wisp (wipes local
  keys and wallet data), install the fork APK.
- OPEN DECISION: keep `com.wisp.app` (users must uninstall upstream
  first; Zapstore collides with upstream listing) or switch to a
  distinct `com.wisp.app.fork` + app_name "Wisp Fork" for side-by-side
  install. The download page and release notes state the uninstall-first
  path until this is decided.

## 5. Distribution hosting — DECISION
- `dist/index.html` uses relative links (`./wisp-<version>.apk`,
  `./SHA256SUMS`); `dist/publish.sh` assembles a complete staging/
  bundle (page + metadata + notes + APK + checksums) from the canonical
  GitHub Release asset for rsync to the static host.
  Whoever controls wisp.rtard.de DNS/hosting must confirm before the
  links go live; otherwise point the links at the GitHub release URL.

## 6. Zapstore
- `docs/fork-release/zapstore.yaml.draft` is a placeholder, not a valid
  manifest: Zapstore's zsp CLI expects fields like repository, pubkey,
  name, summary, description, license, tags, release_filter, match.
  Run `zsp init` to generate a real one, register the release signing
  certificate, and confirm the APK URL resolves. Do not submit before
  §3 and §5.

## 7. Pre-launch verification (local, already done once)
- `apksigner verify --print-certs` → CN=Wisp Fork Release, cert SHA-256
  `3b99014bdceabeaa0575d8c6aac39150d6fdde78371140384789e0eb3590256a`.
- versionCode 92, versionName 1.3.0-fork.1, applicationId com.wisp.app.
- `dist/release.json` `sha256` is null until `dist/publish.sh` fills it
  from the canonical CI release artifact (the cert SHA-256 pin is the
  durable trust anchor and stays hardcoded).
- Still to do: install on a device/emulator, send/receive a DM, run a
  zap end-to-end once the API key decision is made.

## 8. Relation to upstream
- Base: barrydeen/wisp `main` @ 09e26e0e (all merged security PRs).
- Carries unmerged work as commits: #675 (strict 2-file version Barry
  asked for, cherry-pick of 8389f871), the #690 mnemonic stack, and the
  `fix/ssrf-and-media-hardening` branch (LNURL chunked-body fix, SSRF
  guards, DM media auto-fetch gating, Bolt11 hardening, crash redaction).
- The cap/SSRF-guards/checksum work already landed upstream via #687 —
  nothing was dropped.
- barrydeen/wisp is ARCHIVED (read-only): open PRs #675/#690 cannot be
  merged or closed upstream; every future fork tag must be cut from
  this release lineage (rebase onto upstream only if it ever un-
  archives or re-forks).
- Keep the release branch `release/v1.3.0-fork.1` on
  github.com/mptfire/wisp so every published binary maps to public
  source.

## 9. Reproducibility
- Builds pinned to Temurin JDK 17 (CI via setup-java). Locally, invoke
  gradle with `JAVA_HOME=~/jdks/jdk-17.0.21.1+2` — Fedora's system Java
  25 (25.0.4.1) breaks the Kotlin compiler's version parser. Do NOT pin
  `org.gradle.java.home` globally in `~/.gradle/gradle.properties`; it
  would force JDK 17 on every Gradle project on the machine.
- R8 output is not bit-identical across environments: verify the
  **signing certificate**, not only the file hash, when in doubt.
- This page/manifest contains no analytics and makes no network calls.
