## Wisp fork 1.3.0-fork.1 (versionCode 92)

Security-focused fork release of Wisp, based on upstream `main` at
`09e26e0e` plus two open upstream PRs landed here:

- **DM seal authentication** (upstream #675): kind-17 rumor authors are
  authenticated against the seal's signature and bound to the wrapping
  event author before a message is trusted.
- **Fail-closed BIP-39 validation** (upstream #690): recovery-phrase
  checks now validate wordlist membership and checksum, normalize the
  Unicode spaces PDFs and note apps insert, and fail closed if the
  wordlist is missing or truncated.
- Carries all previously merged hardening: LNURL invoice verification,
  zap receipt consistency checks, SSRF guards on LNURL discovery, and
  bech32 checksum validation on BOLT-11 invoices.

### Notes
- Same application ID as upstream (`com.wisp.app`): installing replaces
  an existing upstream build. Back up your recovery phrase first.
- arm64-v8a only, Android 8.0+.
- Verify the APK against `SHA256SUMS`; signing certificate SHA-256:
  `3b99014bdceabeaa0575d8c6aac39150d6fdde78371140384789e0eb3590256a`.
