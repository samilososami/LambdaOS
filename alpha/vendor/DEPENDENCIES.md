# External security-tool dependencies

The public repository does not vendor the following independent Git histories.
They remain external dependencies and are excluded by `.gitignore` when checked
out under `alpha/vendor/`.

| Dependency | Pinned revision | Upstream |
|---|---|---|
| OneShot | `5b4af67` | <https://github.com/kimocoder/OneShot> |
| OneShot-Extended | `3f19bb0` | <https://github.com/chkndrp/OneShot-Extended> |
| Pixiewps | `30c22cf` | <https://github.com/wiire-a/pixiewps> |

LambdaOS publishes its integration adapter and guards, not copies of these
repositories. Check out the exact revision required by the relevant development
script and retain the upstream license files. Dependency presence does not imply
that a radio workflow has passed hardware validation.
