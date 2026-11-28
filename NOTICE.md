# Attribution and licensing

LambdaOS builds on Android and LineageOS. Copyright, SPDX and license notices
included with upstream code and modified files remain in place. Publication
does not replace the licenses of those components.

- **SetupWizard:** LineageOS/AOSP-derived code; see
  [`alpha/apps/SetupWizard/LICENSES/Apache-2.0.txt`](alpha/apps/SetupWizard/LICENSES/Apache-2.0.txt)
  and its `REUSE.toml` and source headers. Lambda-specific setup modifications
  are included in this snapshot.
- **Platform integration:** existing AOSP, LineageOS and other upstream notices
  apply to the affected components, including Settings, SystemUI, Telephony and
  Seedvault. Patch contents retain their upstream context.
- **Nimbus Sans:** the font license is retained in the relevant application and
  platform resources and in the [design asset folder](assets/design/round-07-setup/assets/NimbusSans-LICENSE.txt).
- **Montserrat:** see the [retained font license](alpha/platform/SystemUI/licenses/Montserrat-LICENSE.txt).
- **Lucide:** the icon [license](assets/design/round-07-setup/assets/lucide/LICENSE)
  and [source list](assets/design/round-07-setup/assets/lucide/SOURCES.md) accompany
  the SVG files.
- **Fingerprint artwork:** see the [source and license record](alpha/platform/SystemUI/licenses/fingerprint-recognition.txt).
- **OneShot-Extended / Pixiewps:** LambdaOS publishes a GPL-2.0-or-later WPS
  integration adapter and pins the independent upstream repositories in
  [`alpha/vendor/DEPENDENCIES.md`](alpha/vendor/DEPENDENCIES.md). Their complete
  source histories and license files are obtained from the named upstreams.
- **Aircrack-ng and wireless-tools:** development radio helpers retain their
  upstream GPL license obligations and pinned input hashes. This repository
  publishes the Lambda guards/integration source, not downloaded executables.
- **Probable-Wordlists:** the optional WPA dictionaries are downloaded from the
  pinned upstream and normalized by the published scripts. The generated lists
  are not committed; upstream attribution is CC-BY-SA-4.0.
- **noVNC:** an unmodified vendored copy at
  [`alpha/web/lex-dashboard/public/novnc/`](alpha/web/lex-dashboard/public/novnc/)
  is MPL-2.0, with BSD-2-Clause terms for `core/crypto/des.js` and MIT and zlib
  terms for the bundled pako library; see its
  [`LICENSE.txt`](alpha/web/lex-dashboard/public/novnc/LICENSE.txt).
- **Inter:** the typeface is licensed under the SIL Open Font License 1.1; see
  [`inter.OFL.txt`](alpha/web/lex-dashboard/public/inter.OFL.txt).

The `assets/design/**/references` folders contain design reference material;
third-party marks shown there are not LambdaOS branding or endorsements.
Original concepts and generated artwork are archived for the project's design
work. This publication does not assign a new blanket license to all artwork,
trademarks or newly authored code without an explicit license notice.

No public license has yet been selected for the project-specific material that
has no existing license notice. Contact the maintainer about reuse of that
material. This does not restrict rights already granted by upstream licenses.
