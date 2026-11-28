# Third-Party Notices (Fire Wisp)

## On-Device Translation Engine & Models

- **slimt** (`third_party/slimt`): Licensed under the **GNU General Public License v2.0 (GPL-2.0)** (`third_party/slimt/LICENSE`). Compiled into `libslimt_jni.so` (`arm64-v8a`) for local neural machine translation inference.
- **SentencePiece** (`third_party/slimt/3rd-party/sentencepiece`): Licensed under **Apache License 2.0**.
- **ruy** (`third_party/slimt/3rd-party/ruy`): Licensed under **Apache License 2.0**.
- **Mozilla Firefox Translations Models** (`https://github.com/mozilla/translations`): Unmodified neural machine translation models licensed under **Mozilla Public License 2.0 (MPL-2.0)**, self-hosted as compressed static archives at `https://firewisp.de/models` and verified client-side by pinned SHA-256 checksums before installation.

Because `libslimt_jni.so` links `slimt` (GPL-2.0), distributed Fire Wisp APKs containing `libslimt_jni.so` are distributed under the terms of **GPL-2.0**.
