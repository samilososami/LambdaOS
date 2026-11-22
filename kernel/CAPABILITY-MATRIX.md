# LambdaOS kernel capability matrix

Runtime update, 31 October 2026: the installed FCM-v4 ROM contains all 95
modules. Internal Nexmon monitor reception and AWUS036ACH/RTL8812AU monitor
reception on channel 6 are now hardware-verified. USB/IP VHCI loading caused a
kernel panic and must remain disabled until its prepared kernel fix is tested.
The raw device evidence and logs remain in the private validation archive; this
public matrix retains only the reviewed capability boundary.

Status meanings:

- **Configured**: present in the generated Kconfig.
- **Built**: present in the successful capability-v2 kernel/module build.
- **Integration pending**: firmware, modules, init or SELinux still need ROM work.
- **Hardware pending**: no claim of functionality until tested on the S10 and
  the named adapter.

| Area | Capability / families | Kernel state | Remaining work |
|---|---|---:|---|
| Internal Bluetooth | Broadcom path, BR/EDR, LE, HCI UART | Configured | Regression test on S10 |
| Bluetooth serial | RFCOMM and RFCOMM TTY | Built-in | Pairing and RFCOMM loop test |
| Bluetooth USB | Broadcom, Realtek, Atheros, Intel, Qualcomm and Marvell HCI paths | Configured | Firmware/module packaging and representative dongles |
| Internal Wi-Fi | Samsung Broadcom BCM4375B1 managed mode | Preserved | Normal Wi-Fi regression test |
| Internal Wi-Fi audit | BCM4375B1 / Nexmon | Running patched firmware and monitor reception verified, 2.4 GHz | Injection and other channels/bands not yet tested |
| MediaTek USB Wi-Fi | MT7601U | Built-in and built | Firmware and adapter test |
| Ralink/MediaTek legacy USB Wi-Fi | RT2500USB, RT73USB, RT2800USB RT33xx/35xx/3573/53xx/55xx | Built-in | Firmware and representative adapter matrix |
| MediaTek mt76 USB Wi-Fi | MT7610U and MT7612U/MT7662U | 4.14 backport built as five coherent modules; firmware bundled | ROM integration, module load order, adapter, monitor and injection tests |
| Modern MediaTek Wi-Fi | MT7921/MT7925 and newer Wi-Fi 6/7 families | Outside this candidate | Separate maintained backport or newer kernel; do not advertise support |
| Atheros USB Wi-Fi | AR9271/AR7010 through ath9k_htc; carl9170; ath6kl USB | Built-in | Firmware, monitor and injection tests |
| Realtek USB Wi-Fi | RTL8187, rtl8xxxu, RTL8188EUS, RTL8812AU, RTL8822BU | Installed; AWUS036ACH RTL8812AU monitor reception verified at 2.4 GHz | Other adapters, 5 GHz, sustained capture and injection remain unverified |
| ZyDAS USB Wi-Fi | ZD1201, ZD1211RW | Built-in | Firmware and hardware test |
| CAN core | RAW, BCM, gateway, VCAN/VXCAN | Configured | Socket and namespace tests |
| CAN protocols | ISO-TP, SLCAN, CAN327/ELM327, HLCAN | Modules built | Package modules; vcan and real adapter tests |
| USB-CAN | EMS, ESD, GS_USB/Candlelight, Kvaser, PEAK, 8devices, Microchip CAN BUS Analyzer | Modules built | Package modules and test available adapters |
| USB serial | CH341, FTDI, PL2303 plus CP210x, Keyspan, MosChip, Qualcomm, Sierra, Option and others | Configured | Package modules; VID/PID smoke tests |
| USB networking | CDC Ethernet/NCM/EEM/MBIM, RNDIS, QMI, AX8817x/AX88179, RTL815x and legacy families | Configured | Package modules; tether/MITM regression tests |
| USB gadget | ADB/FunctionFS, serial, ACM, NCM, ECM, RNDIS, EEM, mass storage, MTP/PTP, MIDI and HID | Configured | Android configfs controller and SELinux policy |
| Extended USB gadget | UAC1/UAC2 audio, UVC webcam, printer | Configured | Userspace controller, descriptors and host interoperability |
| USB inspection | usbmon | Built-in | Permissions and capture test |
| USB/IP | core, host, VHCI and virtual UDC | Built; **VHCI runtime failure** | Do not load VHCI on FCM-v4; null-callback fix prepared but not flashed/tested |
| SDR | HackRF, Airspy, MSi2500, RTL28xxU | Configured | Firmware/modules and radio-specific smoke tests |
| HID userspace | HIDRAW, UHID and ConfigFS HID | Configured | Safe controller, cleanup and host tests |
| Virtual networking | bridge, bridge netfilter, MACVLAN/MACVTAP, IPVLAN/IPVTAP, VETH/TUN | Configured | Package modules and namespace tests |

## Explicit non-claims

- “All MediaTek chipsets” is not a single realistic support target. This matrix
  will grow by chipset family and USB ID after a compatible driver exists.
- `NL80211_TESTMODE`, monitor mode and injection are different capabilities.
  Enabling one does not prove either of the others.
- USB host/device support does not mean every USB peripheral in existence has a
  driver. The profile covers practical classes for LambdaOS and remains
  extensible through modules.
- A successful build is not a hardware result. Rows remain integration/hardware
  pending until the exact S10 path and representative adapters are exercised.
- The candidate does not change bootloader, modem or EFS.
