#!/usr/bin/env python3
"""Minimal IPv6 Router Advertisement sender for the AP interface.

Sends periodic RAs advertising the given ULA prefix with SLAAC (A flag) and
RDNSS, with router lifetime 0 (no IPv6 default route: the AP has no IPv6
upstream, clients keep using IPv4 for the Internet). Runs as root on Android.
"""
import ipaddress
import socket
import struct
import sys
import time


def link_local(iface):
    # /proc/net/if_inet6: addr idx plen scope flags name (scope 0x20=link)
    for line in open('/proc/net/if_inet6'):
        parts = line.split()
        if len(parts) >= 6 and parts[5] == iface and parts[0].startswith('fe80'):
            return ipaddress.IPv6Address(int(parts[0], 16))
    raise SystemExit('no link-local address for ' + iface)


def checksum(src, dst, payload):
    pseudo = src.packed + dst.packed + struct.pack('!I', len(payload)) + b'\x00\x00\x00' + bytes([58])
    data = pseudo + payload
    if len(data) % 2:
        data += b'\x00'
    total = 0
    for i in range(0, len(data), 2):
        total += (data[i] << 8) + data[i + 1]
    total = (total >> 16) + (total & 0xFFFF)
    total = (total >> 16) + (total & 0xFFFF)
    return (~total) & 0xFFFF


def ra(iface, prefix, mac):
    idx = socket.if_nametoindex(iface)
    src = link_local(iface)
    dst = ipaddress.IPv6Address('ff02::1')
    # ICMPv6 RA: type 134, hop limit 64, flags 0, router lifetime 0
    header = struct.pack('!BBHBBHII', 134, 0, 0, 64, 0, 0, 0, 0)
    # option 1: source link-layer address (MAC)
    slla = struct.pack('!BB', 1, 1) + bytes.fromhex(mac.replace(':', ''))
    # option 3: prefix information fd50::/64, on-link + autonomous
    pio = struct.pack('!BBBBIII', 3, 4, 64, 0xC0, 86400, 14400, 0) + prefix.packed
    # option 25: RDNSS fd50::1 (22 bytes padded to a 24-byte option)
    rdnss = struct.pack('!BBI', 25, 3, 1200) + ipaddress.IPv6Address(int(prefix) + 1).packed + b'\x00\x00'
    payload = header + slla + pio + rdnss
    packet = payload[:2] + struct.pack('!H', checksum(src, dst, payload)) + payload[4:]
    sock = socket.socket(socket.AF_INET6, socket.SOCK_RAW, socket.IPPROTO_ICMPV6)
    sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_HOPS, 255)
    sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_IF, idx)
    sock.sendto(packet, (dst.compressed, 0, 0, idx))
    sock.close()


def main():
    iface, prefix_arg = sys.argv[1], sys.argv[2]
    prefix = ipaddress.IPv6Address(prefix_arg)
    mac = open('/sys/class/net/' + iface + '/address').read().strip()
    while True:
        try:
            ra(iface, prefix, mac)
        except Exception:
            pass
        time.sleep(8)


if __name__ == '__main__':
    main()
