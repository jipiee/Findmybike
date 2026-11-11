# FindMyBike: data collection protocol

## Before each trip
1. Location (GPS) ON. Battery saver OFF.
2. One-time setup: **Settings → Apps → FindMyBike → Battery → Unrestricted**. Samsung otherwise may stop recording in your pocket.
3. Open the app and choose the **phone position**. Keep the same position and orientation for every trip if possible.
4. Tap **Start trip** near the bike. Wait until the status shows a GPS fix (±10 m or better) before riding off.

## During the trip (tap ONLY when stopped and safe; skip a label rather than tap while riding)
| Button | When |
|---|---|
| **Ride start** | Just before you start pedalling (after unlocking) |
| **Short stop** | Stopped at a traffic light / crossing / waiting for someone |
| **Pushing bike** | When you start walking next to the bike |
| **PARKED HERE** | **The moment you lock the bike.** This is the most important label: ground-truth time + location |
| **Undo last** | You tapped the wrong button (post-processing removes the previous event) |

## After parking
- Keep recording while you **walk away for 1–2 minutes**. The walking-away pattern is what the detector must learn.
- Tap **End trip**, then type notes: indoor/outdoor parking, which floor, rack type, weather, anything unusual.
- **Share last trip (.zip)** → Google Drive / KakaoTalk / email, so the team gets the data.

## Variety to aim for
- Different parking places: outdoor racks, building basements / upper floors, street parking against a fence.
- Different stops: traffic lights, brief stops at shops, pushing the bike through crowds.
- Different days / routes / weather.
- A few "no parking" trips too (stops only), so the detector learns what NOT to flag.

## Files in each trip_<date>_<time>/ (all times t_us = µs since phone boot, same clock for every file)
| File | Contents | Rate |
|---|---|---|
| accel.csv | ax, ay, az (m/s², incl. gravity) | 100 Hz |
| gyro.csv | gx, gy, gz (rad/s) | 100 Hz |
| mag.csv | mx, my, mz (µT, calibrated) | 50 Hz |
| mag_uncal.csv | raw mx, my, mz + hard-iron bias bx, by, bz (µT) | 50 Hz |
| baro.csv | air pressure (hPa); 0.12 hPa ≈ 1 m height | ~10 Hz |
| gps.csv | utc, lat, lon, accuracy, altitude, speed, bearing | 1 Hz |
| gnss.csv | satellites in view / used, mean signal strength (drops indoors) | ~1 Hz |
| events.csv | your labels + GPS position at that moment | on tap |
| battery.csv | battery % | every 30 s |
| meta.json | phone, position, rider, bike, notes, sensor specs, start/end clock times | n/a |

Check a trip on a Mac: `python3 view_trip.py trip_<...>` → trip_overview.png
