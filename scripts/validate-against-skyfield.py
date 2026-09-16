#!/usr/bin/env python3
"""Validates the pass reference produced by Orekit against Skyfield.

Skyfield is an independent implementation of SGP4, in Python, sharing no code with
Orekit. If the two agree, the project's implementation and chain of frames are
correct.

The naive comparison -- asking Skyfield for its passes and comparing dates -- gives
discrepancies of up to a second, without saying which of the two is wrong: Skyfield's
find_events is documented as accurate to the second. So we work the other way round:
we take the dates produced by Orekit and ask Skyfield what elevation and azimuth it
computes at those exact instants. If Orekit is right, Skyfield must find exactly the
threshold at the boundaries of the pass, and the announced maximum at the culmination.

Four checks:

  1. Boundaries   -- at AOS and LOS, Skyfield finds the threshold elevation.
  2. Culmination  -- at the time of the maximum, Skyfield finds the announced maximum
                     elevation, and confirms it really is a local maximum.
  3. Azimuths     -- the three azimuths of each pass agree.
  4. Completeness -- Skyfield finds no pass that Orekit would have missed. This check
                     is the only one able to detect a pass skipped by Orekit's 60 s
                     detection step; the other three only verify passes already found.

Usage:        python3 scripts/validate-against-skyfield.py
Prerequisite: pip install skyfield
Output:       exit code 0 if everything agrees, 1 otherwise.
"""

import datetime as dt
import json
import pathlib
import sys

try:
    from skyfield.api import EarthSatellite, load, wgs84
except ImportError:
    sys.exit("skyfield is missing. Install it: pip install skyfield")

# Tolerances. The discrepancies actually observed on 16/09/2026 are 0.53 millidegree in
# elevation and 2.0 millidegrees in azimuth. The thresholds below therefore leave a factor
# of 10 to 20 of margin: they detect a model or frame error, not the rounding noise
# between two implementations.
ELEVATION_TOLERANCE_DEG = 0.010
AZIMUTH_TOLERANCE_DEG = 0.020

REFERENCE = (pathlib.Path(__file__).resolve().parent.parent
             / "backend/src/test/resources/validation/iss-lyon-reference.json")


def parse_instant(text):
    return dt.datetime.fromisoformat(text.replace("Z", "+00:00"))


def angular_difference(a, b):
    """Difference between two azimuths, accounting for the wrap at 360 degrees."""
    return abs((a - b + 180.0) % 360.0 - 180.0)


def main():
    reference = json.loads(REFERENCE.read_text())
    timescale = load.timescale(builtin=True)
    satellite = EarthSatellite(reference["satellite"]["tleLine1"],
                               reference["satellite"]["tleLine2"],
                               reference["satellite"]["name"], timescale)
    observer = reference["observer"]
    site = wgs84.latlon(observer["latitudeDeg"], observer["longitudeDeg"],
                        elevation_m=observer["altitudeMeters"])
    difference = satellite - site
    threshold = reference["minElevationDeg"]

    def look(instant):
        altitude, azimuth, _ = difference.at(timescale.from_datetime(instant)).altaz()
        return altitude.degrees, azimuth.degrees

    failures = []
    worst_elevation = 0.0
    worst_azimuth = 0.0

    print(f"Reference: {REFERENCE.name}")
    print(f"Satellite: {reference['satellite']['name']} "
          f"(NORAD {reference['satellite']['noradId']})")
    print(f"Observer : {observer['name']} "
          f"({observer['latitudeDeg']}, {observer['longitudeDeg']}, "
          f"{observer['altitudeMeters']} m)")
    print(f"Threshold: {threshold} degrees\n")
    print(f"{'pass':>8} {'point':>11} {'elevation error':>18} {'azimuth error':>16}")
    print("-" * 58)

    for index, satellite_pass in enumerate(reference["passes"], start=1):
        points = (
            ("AOS", satellite_pass["aos"], threshold, satellite_pass["aosAzimuthDeg"]),
            ("culmination", satellite_pass["maxElevationTime"],
             satellite_pass["maxElevationDeg"], satellite_pass["maxElevationAzimuthDeg"]),
            ("LOS", satellite_pass["los"], threshold, satellite_pass["losAzimuthDeg"]),
        )
        for label, when, expected_elevation, expected_azimuth in points:
            instant = parse_instant(when)
            elevation, azimuth = look(instant)
            elevation_error = abs(elevation - expected_elevation)
            azimuth_error = angular_difference(azimuth, expected_azimuth)
            worst_elevation = max(worst_elevation, elevation_error)
            worst_azimuth = max(worst_azimuth, azimuth_error)

            flag = ""
            if elevation_error > ELEVATION_TOLERANCE_DEG:
                failures.append(f"pass {index} {label}: Skyfield elevation "
                                f"{elevation:.6f}, expected {expected_elevation:.6f}")
                flag = "  FAILED"
            if azimuth_error > AZIMUTH_TOLERANCE_DEG:
                failures.append(f"pass {index} {label}: Skyfield azimuth "
                                f"{azimuth:.6f}, expected {expected_azimuth:.6f}")
                flag = "  FAILED"

            print(f"{index:>8} {label:>11} {elevation_error * 1000:>15.3f} md "
                  f"{azimuth_error * 1000:>13.3f} md{flag}")

        # The culmination must be a local maximum, not just any point of the pass.
        apex = parse_instant(satellite_pass["maxElevationTime"])
        before, _ = look(apex - dt.timedelta(seconds=5))
        after, _ = look(apex + dt.timedelta(seconds=5))
        if before > satellite_pass["maxElevationDeg"] or after > satellite_pass["maxElevationDeg"]:
            failures.append(f"pass {index}: the announced culmination is not a local "
                            f"maximum ({before:.4f} / "
                            f"{satellite_pass['maxElevationDeg']:.4f} / {after:.4f})")

    # Completeness: Skyfield looks for the passes on its own account.
    epoch = satellite.epoch
    end = timescale.tt_jd(epoch.tt + reference["window"]["hours"] / 24.0)
    times, kinds = satellite.find_events(site, epoch, end, altitude_degrees=threshold)
    complete = 0
    state = None
    for kind in kinds:
        if kind == 0:
            state = "risen"
        elif kind == 2 and state == "risen":
            complete += 1
            state = None
    print(f"\nCompleteness: Skyfield finds {complete} complete passes, "
          f"Orekit produced {len(reference['passes'])}.")
    if complete != len(reference["passes"]):
        failures.append(f"number of passes: Skyfield {complete}, "
                        f"Orekit {len(reference['passes'])}")

    print(f"\nLargest elevation discrepancy: {worst_elevation * 1000:.3f} millidegree "
          f"(tolerance {ELEVATION_TOLERANCE_DEG * 1000:.0f})")
    print(f"Largest azimuth discrepancy  : {worst_azimuth * 1000:.3f} millidegree "
          f"(tolerance {AZIMUTH_TOLERANCE_DEG * 1000:.0f})")

    if failures:
        print(f"\n{len(failures)} discrepancy/ies outside tolerance:")
        for failure in failures:
            print(f"  - {failure}")
        return 1

    print("\nOrekit and Skyfield agree across the whole reference.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
