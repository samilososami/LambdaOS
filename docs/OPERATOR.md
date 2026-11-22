# Lambda Operator status

Snapshot date: 22 November 2026. Package `org.lambdaproject.lambda`, development
version `0.2.0-operator.4` (`versionCode 24`).

## Implemented in the published source

- Native hardware inventory and Operator navigation.
- Internal/external Wi-Fi source selection and capability-aware states.
- Target-specific capture orchestration with airodump-ng status parsing.
- One separately confirmed and bounded active-assistance request during capture.
- Durable capture/cracking receipts and fresh-session behavior.
- Automatic 4,800-entry quick check and a 203,109-entry dedicated dictionary
  pipeline, both constrained to valid WPA passphrase lengths.
- ARM64 CPU and CPU + Mali hybrid PMK implementations with process, memory and
  thermal guards.
- Independent verification of a reported candidate before it is persisted.
- Connect/save/autojoin only from a verified result bound to the target.
- WPS Pixie Dust, PIN, push-button and bounded PIN-batch workflow state.
- A foreground Core service and restricted provider boundary.

## Deployment boundary

The tested development arrangement combines the signed APK with a separately
provisioned restricted worker and native tools. The worker is not yet a complete
ROM-persistent service and may require reprovisioning after reboot. The standalone
APK build does not contain all runtime dependencies.

The current WPS update passed synthetic adapter, contract, guard and UI checks.
Those checks do not establish a real router exchange. Radio behavior is claimed
only where the exact hardware path has separate device evidence.

## Intentionally not claimed

- arbitrary root-shell access;
- universal adapter compatibility;
- automatic authorization based on discovered networks;
- password recovery without a cryptographically verified candidate;
- measured client impact from tool-reported frame transmission;
- a published, persistent production ROM deployment.

Performance data is summarized in [OPERATOR-BENCHMARKS.md](OPERATOR-BENCHMARKS.md).
