# Operator cracking benchmarks

These measurements are development observations on the reference Exynos Galaxy
S10. They use synthetic or explicitly authorized fixtures and are not sustained
performance guarantees.

| Workload | Engine | Observed result |
|---|---|---:|
| 4,800 negative candidates | optimized CPU | median 1.351 s |
| 4,800 negative candidates | CPU + Mali hybrid | median 2.516 s |
| 96,000 negative candidates | optimized CPU | 25.603 s / 3,749.56 candidates/s |
| 96,000 negative candidates | CPU + Mali hybrid | 16.559 s / 5,797.45 candidates/s |
| 203,109-entry APK run | hybrid end-to-end path | one run: 32.557 s / 6,238.57 candidates/s |
| 19,200 PMK-only batch | hybrid prototype | 1.888 s / approximately 10,170 PMKs/s |

The PMK-only number excludes setup and final handshake verification and must not
be reported as end-to-end password attempts per second. On the longer workload,
the hybrid path improved throughput by approximately 54.6% and reduced elapsed
time by approximately 35.3% relative to the optimized CPU path.

Hashcat is not the working mobile engine: tested Mali paths failed correctness,
self-test or memory gates. The retained production direction is optimized
Aircrack-compatible verification plus the purpose-built CPU/Mali helper.

During long development tests, CPU-labelled sensors reached 82 °C and battery
temperature reached 36.6 °C. Current guards stop work at a 42 °C battery reading
or 85 °C SoC reading. Sensor naming and thermal behavior depend on the device
build, ambient conditions and workload.
