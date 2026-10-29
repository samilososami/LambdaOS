# LambdaOS roadmap

Updated 29 October 2026. The current priority is the operating system and its
usable capabilities. This roadmap describes intended work, not released features.

## Established foundation

- A booting Android 15 / LineageOS 22.2 alpha on the Exynos Galaxy S10 SM-G973F.
- Lambda setup, boot artwork, wallpapers, clocks and monochrome system styling.
- A Lambda Operator foundation for device information and future tool workflows.
- Local build/signing work and device testing records.
- A preserved Lamb model/agent research checkpoint.

## 1. System foundation and recovery

Record the exact source and firmware requirements, refine build reproducibility,
and prepare a tested recovery path. Stabilize system services, updates and the
configuration needed for a public release.

## 2. Kernel and hardware capabilities

Establish a capability matrix for the S10 and external adapters. Select and
build a kernel candidate against concrete requirements; validate drivers,
modules, USB behavior, stability and power use on the device.

Monitor mode, injection, HID, CAN and radio support will be documented only
where the required hardware and software have been tested.

## 3. Lambda Core and command interfaces

Define versioned operations with structured arguments, results and errors.
Build a common execution layer for the CLI, Operator and the future assistant,
including permission handling, cancellation and observable job state. Work on
existing Android capabilities can proceed alongside kernel development.

## 4. Lambda Operator

Turn the application foundation into useful, coherent workflows. Keep GUI and
CLI state synchronized, communicate required adapters clearly and distinguish
available operations from unsupported capabilities. Refine motion, accessibility
and feedback as the functionality becomes real.

## 5. Public releases

Package reviewed components into reproducible, signed ROMs. Publish checksums,
device-specific installation notes, upgrade behavior and known issues through
GitHub Releases. Complete the public build and source-distribution requirements
before the first flashable release.

## 6. Resume Lamb

Model development is **paused at the 29 October 2026 checkpoint**. Resume once
the operating system's operations, permissions, hardware support and output
formats are stable enough to produce representative training examples.

The next model work will first complete the untrained export comparisons, then
test a small training/export round trip. A broader fine-tune follows only when
learning and mobile performance are verified. No training has begun at this
checkpoint. See [checkpoint details](docs/LAMB-CHECKPOINT.md).

## Later exploration

Recovery interface design, screen sharing, desktop workflows, voice activation
and a modular USB-C hardware accessory remain exploratory ideas. They do not
carry support or delivery commitments yet.
