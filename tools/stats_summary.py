#!/usr/bin/env python3
"""Summarize stats_*.csv files written by the app's resource monitor (ResourceMonitor.java).

usage: tools/stats_summary.py <stats.csv>...

Works the same for files from the car's USB stick (<log dir>/stats/) and from the emulator
(adb pull /sdcard/ODA/stats/).
"""
import csv
import sys
from collections import defaultdict

COLUMNS = [
    ("proc_cpu_pct", "app CPU % (of one core)"),
    ("sys_cpu_pct", "system CPU % (all cores)"),
    ("total_pss_kb", "app total PSS (KB)"),
    ("java_used_kb", "Java heap used (KB)"),
    ("native_alloc_kb", "native heap allocated (KB)"),
    ("other_pss_kb", "other PSS, incl. graphics/codecs (KB)"),
    ("sys_avail_kb", "system free RAM (KB)"),
    ("threads", "threads"),
]


def summarize(path):
    with open(path, newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        print(f"{path}: no samples")
        return

    duration = rows[-1]["uptime_s"]
    print(f"== {path}: {len(rows)} samples over {duration}s")
    java_max = rows[-1]["java_max_kb"]
    print(f"   Java heap limit: {java_max} KB")
    low = sum(1 for r in rows if r["sys_low_memory"] == "true")
    if low:
        print(f"   system reported LOW MEMORY in {low} samples")

    print(f"   {'':40} {'avg':>10} {'max':>10} {'last':>10}")
    for key, label in COLUMNS:
        values = [float(r[key]) for r in rows if r.get(key)]
        if not values:
            continue
        print(f"   {label:40} {sum(values) / len(values):10.1f} {max(values):10.1f} {values[-1]:10.1f}")

    # busiest threads: average CPU % over all samples, by thread name
    totals = defaultdict(float)
    for r in rows:
        for entry in filter(None, r["top_threads"].split("|")):
            name, _, pct = entry.rpartition(":")
            totals[name] += float(pct)
    if totals:
        print("   busiest threads (avg CPU % of one core):")
        for name, total in sorted(totals.items(), key=lambda t: -t[1])[:10]:
            print(f"     {name:30} {total / len(rows):6.1f}")
    print()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for p in sys.argv[1:]:
        summarize(p)
