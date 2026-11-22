# Architecture

LambdaOS is developed as six layers with explicit trust boundaries.

## Lambda Base

The Android 15 / LineageOS 22.2 product for `beyond1lte`: system identity,
SetupWizard, overlays, SystemUI/Settings integrations, boot assets and release
packaging.

## Lambda Kernel

The Exynos 9820 kernel and device-specific capabilities: radio drivers, USB host
and gadget support, SocketCAN, HID, USB networking and the internal Nexmon path.
Configuration, build and hardware verification are tracked separately.

## Lambda Core

The privileged operation layer. Requests are typed, validated and bound to
observed targets and job state. Core owns cancellation, receipts, result
classification and lifecycle; it is not an arbitrary root shell.

## Lambda Operator

The native control centre. It presents capability-aware workflows, keeps UI and
Core state synchronized and distinguishes unavailable, prepared, running,
completed and failed operations.

## Lamb

The local assistant layer. It will consume Core's typed catalogue and results.
Model text cannot authorize or prove an operation. Model work is checkpointed
until the command contracts are stable.

## Recovery and installer

The future installation, validation, snapshot and rollback layer. The current
repository does not publish a custom recovery or a supported flashable release.

## Request flow

```text
Operator or Lamb
      |
      v
typed intent -> policy and target validation -> bounded executor
      ^                                      |
      |                                      v
 UI/job state <- structured result and receipt <- real tool output
```

Authorization belongs to the application/policy layer and is scoped to a
specific operation and target. A model-generated field, SSID, tool exit code or
log line is not authorization or proof of effect.
