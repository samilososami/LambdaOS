# Releases

Flashable packages belong in [GitHub Releases](https://github.com/samilososami/LambdaOS/releases).
This directory records how they will be published; it is not a download directory.

**There is no public flashable ROM in this initial repository snapshot.**
Existing local development builds contain host-specific configuration and do
not constitute a reviewed public distribution. The current source also includes
incremental UI work that has not all been packaged into a new full ROM.

A public release should include:

- The exact model/codename, Android base and source revisions.
- A flashable ZIP and its SHA-256 checksum.
- Signing identity information and the supported upgrade path.
- Firmware prerequisites, recovery instructions and any data-wipe requirement.
- Known issues and the scope of hardware testing.
- Corresponding source information and required upstream notices.

Public builds must not embed a maintainer's ADB authorization key. Private
signing material, personal backups, EFS dumps, local logs and model weights do
not belong in the Git repository.

The development OTA policy preserves bootloader, modem, EFS and `up_param`.
Every future release must state its own exact flashing scope. Do not infer
that a similarly named Samsung variant is supported.

GitHub's automatic source ZIP/TAR downloads are **not flashable ROM images**.

References: [GitHub releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)
and [large-file guidance](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github).
