# Captive portal templates

- `google/` and `router/` are adapted from **FluxionNetwork/fluxion** (GPL-3.0),
  https://github.com/FluxionNetwork/fluxion, paths
  `attacks/Captive Portal/sites/Google_de.portal` and `TP-LINK_en.portal`.
  The PHP server-side handlers are not included: the LambdaOS radio worker
  serves the static files and captures form submissions itself.
  The template files remain under GPL-3.0; see the upstream repository for the
  full license text.
- `simple/` is original LambdaOS content with no third-party assets.
- These templates exist for authorized security testing on the owner's own or
  explicitly authorized network, behind Lambda Operator's target authorization.
