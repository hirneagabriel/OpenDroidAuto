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

    # video, over samples where video was arriving
    vrows = [r for r in rows if r.get("video_fps") and float(r["video_fps"]) > 0]
    if vrows:
        fps = [float(r["video_fps"]) for r in vrows]
        kbps = [float(r["video_kb_per_s"]) for r in vrows]
        frame = [float(r["video_max_frame_kb"]) for r in vrows]
        gaps = sorted(float(r["video_max_gap_ms"]) for r in vrows)
        print(f"   video ({len(vrows)} samples with video):")
        print(f"     frames/s             avg {sum(fps) / len(fps):6.1f}   max {max(fps):6.1f}")
        print(f"     KB/s                 avg {sum(kbps) / len(kbps):6.1f}   max {max(kbps):6.1f}")
        print(f"     largest frame (KB)                max {max(frame):6.0f}")
        print(f"     longest gap (ms)  median {gaps[len(gaps) // 2]:6.0f}   max {gaps[-1]:6.0f}")
        rendered = [float(r["video_rendered_fps"]) for r in vrows if r.get("video_rendered_fps")]
        queued = [int(r["video_max_queued"]) for r in vrows if r.get("video_max_queued")]
        if rendered:
            print(f"     rendered frames/s    avg {sum(rendered) / len(rendered):6.1f}   max {max(rendered):6.1f}")
        if queued:
            print(f"     frames waiting for decoder  avg peak {sum(queued) / len(queued):4.1f}   max {max(queued):4d}")

        lat_avg = [float(r["video_latency_avg_ms"]) for r in vrows if r.get("video_latency_avg_ms")]
        lat_max = [float(r["video_latency_max_ms"]) for r in vrows if r.get("video_latency_max_ms")]
        if lat_avg:
            print(f"     arrival->screen (ms) avg {sum(lat_avg) / len(lat_avg):6.1f}   max {max(lat_max):6.0f}")

        # where arrival->screen goes (OMX decoder only): waiting for the decoder, inside it, hand-off to the screen
        def avg_of(col):
            vals = [float(r[col]) for r in vrows if r.get(col)]
            return (sum(vals) / len(vals), max(vals)) if vals else None
        wait, decode, decode_max, handoff = (avg_of(c) for c in
            ("video_wait_ms", "video_decode_avg_ms", "video_decode_max_ms", "video_handoff_ms"))
        in_dec, in_dec_max = avg_of("video_in_decoder_avg"), avg_of("video_in_decoder_max")
        if decode:
            print(f"       waiting for decoder (ms)  avg {wait[0]:6.1f}")
            print(f"       inside decoder (ms)       avg {decode[0]:6.1f}   max {decode_max[1]:6.0f}")
            print(f"       hand-off to screen (ms)   avg {handoff[0]:6.1f}")
            print(f"       frames held by decoder    avg {in_dec[0]:6.1f}   max {in_dec_max[1]:6.0f}")

    summarize_touch(rows)
    summarize_system(rows)

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


def floats(rows, key):
    return [float(r[key]) for r in rows if r.get(key) not in (None, "")]


def summarize_touch(rows):
    touch_rows = [r for r in rows if r.get("touch_events") and int(r["touch_events"]) > 0]
    if not touch_rows:
        return
    events = sum(int(r["touch_events"]) for r in touch_rows)
    moves = sum(int(r["touch_moves"]) for r in touch_rows)
    history = sum(int(r["touch_history"]) for r in touch_rows)
    move_rows = [r for r in touch_rows if int(r["touch_moves"]) > 0]
    print(f"   touch ({len(touch_rows)} samples with touches, {len(move_rows)} with movement):")
    if move_rows:
        rate = [int(r["touch_moves"]) / 5.0 for r in move_rows]
        print(f"     move events/s        avg {sum(rate) / len(rate):6.1f}   max {max(rate):6.1f}")
    print(f"     events {events}, moves {moves}, extra samples batched in events {history}")
    age_max = floats(touch_rows, "touch_age_max_ms")
    age_avg = floats(touch_rows, "touch_age_avg_ms")
    print(f"     event age at app (ms) avg {sum(age_avg) / len(age_avg):6.1f}   max {max(age_max):6.0f}")
    print(f"     app hand-off (ms)                 max {max(floats(touch_rows, 'touch_handoff_max_ms')):6.1f}")
    send_avg = floats(touch_rows, "touch_send_avg_ms")
    if send_avg:
        print(f"     sent to phone (ms)    avg {sum(send_avg) / len(send_avg):6.1f}   max {max(floats(touch_rows, 'touch_send_max_ms')):6.1f}")
    n = [int(r["touch_to_frame_n"]) for r in touch_rows if r.get("touch_to_frame_n")]
    if sum(n):
        weighted = sum(int(r["touch_to_frame_n"]) * float(r["touch_to_frame_avg_ms"]) for r in touch_rows if r.get("touch_to_frame_n"))
        print(f"     response: touch on still screen -> first frame (ms) avg {weighted / sum(n):6.1f}   max {max(floats(touch_rows, 'touch_to_frame_max_ms')):6.0f}   ({sum(n)} touches)")
    no_response = sum(int(r["touch_no_response"]) for r in rows if r.get("touch_no_response"))
    print(f"     touches on a still screen without a new frame within 1 s: {no_response}")


def summarize_system(rows):
    pings = [r for r in rows if r.get("ping_n") and int(r["ping_n"]) > 0]
    if pings:
        avg = floats(pings, "ping_avg_ms")
        print(f"   ping to phone (ms)     avg {sum(avg) / len(avg):6.1f}   max {max(floats(pings, 'ping_max_ms')):6.1f}")
    lag = sorted(floats(rows, "main_lag_max_ms"))
    if lag:
        print(f"   app main thread stall (ms, per sample max) median {lag[len(lag) // 2]:5.0f}   max {lag[-1]:5.0f}")
    online = defaultdict(int)
    for r in rows:
        if r.get("cpu_online"):
            online[r["cpu_online"]] += 1
    if online:
        print("   cpu cores online: " + ", ".join(f"{k} ({v} samples)" for k, v in sorted(online.items())))
    mhz = defaultdict(list)
    for r in rows:
        for i, v in enumerate((r.get("cpu_mhz") or "").split("/")):
            if v.isdigit():
                mhz[i].append(int(v))
    if mhz:
        print("   cpu MHz per core (min/avg/max): " + ", ".join(
            f"cpu{i} {min(v)}/{sum(v) // len(v)}/{max(v)}" for i, v in sorted(mhz.items())))
    dropped = sum(float(r["audio_dropped_kb"]) for r in rows if r.get("audio_dropped_kb"))
    if any(r.get("audio_dropped_kb") for r in rows):
        print(f"   audio dropped (playback behind): {dropped:.0f} KB")
    totals = defaultdict(float)
    for r in rows:
        for entry in filter(None, (r.get("top_processes") or "").split("|")):
            name, _, pct = entry.rpartition(":")
            totals[name] += float(pct)
    if totals:
        print("   busiest processes (avg CPU % of one core):")
        for name, total in sorted(totals.items(), key=lambda t: -t[1])[:8]:
            print(f"     {name:30} {total / len(rows):6.1f}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for p in sys.argv[1:]:
        summarize(p)
