# LambdaOS roadmap

Updated 22 November 2026. Status labels distinguish published source,
development deployment and physically verified behavior.

## Current checkpoint

- LambdaOS `0.1.0-alpha.2` boots on the reference SM-G973F without GApps.
- The custom setup, boot experience, wallpapers, lock screen and monochrome
  platform styling are implemented in the development line.
- Kernel `4.14.356-lambdaos+` has booted with a 95-module package; selected
  internal and external 2.4 GHz monitor-receive paths are verified.
- Operator `0.2.0-operator.4` includes bounded Wi-Fi capture, WPS and offline
  verification workflows with persistent receipts and thermal limits.
- Lamb model work is preserved and paused; no fine-tuning has started.

## 1. Reproducible system foundation

- Pin the Android/device source revisions and proprietary extraction inputs.
- Turn the private build pipeline into a portable, reviewed public recipe.
- Finish release signing, update behavior, checksums and recovery instructions.
- Publish the first flashable build only after clean-install and upgrade testing.

## 2. Kernel and hardware validation

- Rebuild and physically retest the prepared USB callback guard before enabling
  USB/IP VHCI.
- Complete firmware packaging and fix the read-only vendor module helper in a
  new ROM build.
- Validate supported adapter families by exact USB ID and workflow; keep build
  support separate from monitor/injection/sustained-capture claims.
- Measure stability, power and thermal behavior under longer workloads.

## 3. Lambda Core integration

- Move the restricted development worker into reviewed ROM services and SELinux
  domains.
- Version the typed operation catalogue, errors, job state and receipts.
- Make cancellation and restoration robust across process and system restarts.
- Keep active actions explicitly authorized and target-bound.

## 4. Lambda Operator

- Package runtime dependencies and notices reproducibly.
- Finish accessibility, error recovery and hardware-unavailable states.
- Validate Bluetooth and USB workflows against the same typed Core boundary.
- Keep synthetic tests separate from physical radio validation.

## 5. Recovery and public releases

- Define the Lambda recovery, installation validation, snapshot and rollback
  strategy.
- Publish ROM artifacts through GitHub Releases with device target, SHA-256,
  signing identity, installation notes, upgrade behavior and known issues.

## 6. Resume Lamb

Resume only after Core contracts represent the final operating environment.
First validate the training/export/deployment round trip, then specialize the
model against real typed operations and evaluate it on the S10. Distillation is
conditional on measured memory, latency and energy requirements.

## Later exploration

Wireless ADB in recovery, screen sharing, desktop modes, voice activation,
snapshot workflows and a modular USB-C hardware accessory remain exploratory.
They are not implementation claims or release commitments.
