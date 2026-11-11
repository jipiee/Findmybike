"""
Quick look at one recorded trip: speed, satellites, pressure, motion and magnetic field on one
timeline, with your event labels as vertical lines. Use it after every trip to check the data.

Usage:
    pip3 install pandas matplotlib
    python3 view_trip.py path/to/trip_2026-11-10_083200      (the unzipped trip folder)
Saves trip_overview.png inside that folder.
"""
import json
import os
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd


def load(d, name):
    p = os.path.join(d, name)
    return pd.read_csv(p) if os.path.exists(p) and os.path.getsize(p) > 0 else pd.DataFrame()


def main(d):
    meta = json.load(open(os.path.join(d, "meta.json")))
    t0 = meta["start_elapsed_us"]
    sec = lambda df: (df["t_us"] - t0) / 1e6

    acc, mag, gps = load(d, "accel.csv"), load(d, "mag.csv"), load(d, "gps.csv")
    gnss, baro, ev = load(d, "gnss.csv"), load(d, "baro.csv"), load(d, "events.csv")

    fig, ax = plt.subplots(5, 1, figsize=(13, 12), sharex=True)
    if len(gps):
        ax[0].plot(sec(gps), gps["speed_mps"] * 3.6); ax[0].set_ylabel("speed\nkm/h")
    if len(gnss):
        ax[1].plot(sec(gnss), gnss["sats_used"], label="sats used")
        ax[1].plot(sec(gnss), gnss["mean_cn0_used_dbhz"], label="mean C/N0 dB-Hz")
        ax[1].legend(loc="upper right"); ax[1].set_ylabel("GNSS")
    if len(baro):
        # 1 hPa ≈ 8.3 m near sea level -> relative height
        ax[2].plot(sec(baro), (baro["hpa"].iloc[0] - baro["hpa"]) * 8.3); ax[2].set_ylabel("rel. height\nm (baro)")
    if len(acc):
        mag_a = np.sqrt(acc.ax**2 + acc.ay**2 + acc.az**2)
        ax[3].plot(sec(acc), mag_a.rolling(50, min_periods=1).std(), lw=0.6); ax[3].set_ylabel("|acc| std\n(0.5 s)")
    if len(mag):
        ax[4].plot(sec(mag), np.sqrt(mag.mx**2 + mag.my**2 + mag.mz**2), lw=0.6); ax[4].set_ylabel("|B| µT")
    for _, e in ev.iterrows():
        for a in ax:
            a.axvline((e.t_us - t0) / 1e6, color="red" if e.label == "parked" else "gray", ls="--", lw=0.8)
        ax[0].text((e.t_us - t0) / 1e6, ax[0].get_ylim()[1], e.label, rotation=90, va="top", fontsize=8)
    ax[-1].set_xlabel("seconds since trip start")
    fig.suptitle(f"{meta['trip_id']}  ·  {meta.get('phone_placement', '')}  ·  {meta.get('notes', '')}")
    fig.tight_layout()
    out = os.path.join(d, "trip_overview.png")
    fig.savefig(out, dpi=120)
    print("rows:", meta.get("rows"), "\nsaved", out)


if __name__ == "__main__":
    main(sys.argv[1])
