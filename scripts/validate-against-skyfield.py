#!/usr/bin/env python3
"""Valide la reference de passages produite par Orekit contre Skyfield.

Skyfield est une implementation independante de SGP4, en Python, sans lien de
code avec Orekit. Si les deux tombent d'accord, c'est que l'implementation et la
chaine de reperes du projet sont correctes.

La comparaison naive -- demander ses passages a Skyfield et comparer les dates --
donne des ecarts allant jusqu'a une seconde, sans dire lequel des deux a tort :
le find_events de Skyfield est documente comme precis a la seconde. On procede
donc a l'envers : on prend les dates produites par Orekit et on demande a
Skyfield quelle elevation et quel azimut il calcule a ces instants precis. Si
Orekit a raison, Skyfield doit retrouver exactement le seuil aux bornes du
passage, et l'elevation maximale annoncee au sommet.

Quatre controles :

  1. Bornes    -- a l'AOS et au LOS, Skyfield retrouve l'elevation seuil.
  2. Sommet    -- a l'heure du maximum, Skyfield retrouve l'elevation maximale
                  annoncee, et confirme qu'il s'agit bien d'un maximum local.
  3. Azimuts   -- les trois azimuts de chaque passage concordent.
  4. Exhaustivite -- Skyfield ne trouve pas de passage qu'Orekit aurait manque.
                  Ce controle est le seul qui puisse detecter un passage saute
                  par le pas de detection de 60 s d'Orekit ; les trois autres ne
                  verifient que les passages deja trouves.

Usage :  python3 scripts/validate-against-skyfield.py
Prerequis : pip install skyfield
Sortie : code 0 si tout concorde, 1 sinon.
"""

import datetime as dt
import json
import pathlib
import sys

try:
    from skyfield.api import EarthSatellite, load, wgs84
except ImportError:
    sys.exit("skyfield est absent. Installe-le : pip install skyfield")

# Tolerances. Les ecarts reellement observes au 16/09/2026 sont de 0,53 millidegre
# en elevation et 2,0 millidegres en azimut. Les seuils ci-dessous laissent donc un
# facteur 10 a 20 de marge : ils detectent une erreur de modele ou de repere, pas le
# bruit d'arrondi entre deux implementations.
ELEVATION_TOLERANCE_DEG = 0.010
AZIMUTH_TOLERANCE_DEG = 0.020

REFERENCE = (pathlib.Path(__file__).resolve().parent.parent
             / "backend/src/test/resources/validation/iss-lyon-reference.json")


def parse_instant(text):
    return dt.datetime.fromisoformat(text.replace("Z", "+00:00"))


def angular_difference(a, b):
    """Ecart entre deux azimuts, en tenant compte du passage par 360 degres."""
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

    print(f"Reference : {REFERENCE.name}")
    print(f"Satellite : {reference['satellite']['name']} "
          f"(NORAD {reference['satellite']['noradId']})")
    print(f"Observateur : {observer['name']} "
          f"({observer['latitudeDeg']}, {observer['longitudeDeg']}, "
          f"{observer['altitudeMeters']} m)")
    print(f"Seuil : {threshold} degres\n")
    print(f"{'passage':>8} {'point':>7} {'ecart elevation':>18} {'ecart azimut':>16}")
    print("-" * 54)

    for index, passage in enumerate(reference["passes"], start=1):
        points = (
            ("AOS", passage["aos"], threshold, passage["aosAzimuthDeg"]),
            ("sommet", passage["maxElevationTime"], passage["maxElevationDeg"],
             passage["maxElevationAzimuthDeg"]),
            ("LOS", passage["los"], threshold, passage["losAzimuthDeg"]),
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
                failures.append(f"passage {index} {label} : elevation Skyfield "
                                f"{elevation:.6f}, attendue {expected_elevation:.6f}")
                flag = "  ECHEC"
            if azimuth_error > AZIMUTH_TOLERANCE_DEG:
                failures.append(f"passage {index} {label} : azimut Skyfield "
                                f"{azimuth:.6f}, attendu {expected_azimuth:.6f}")
                flag = "  ECHEC"

            print(f"{index:>8} {label:>7} {elevation_error * 1000:>15.3f} md "
                  f"{azimuth_error * 1000:>13.3f} md{flag}")

        # Le sommet doit etre un maximum local, pas un point quelconque du passage.
        apex = parse_instant(passage["maxElevationTime"])
        before, _ = look(apex - dt.timedelta(seconds=5))
        after, _ = look(apex + dt.timedelta(seconds=5))
        if before > passage["maxElevationDeg"] or after > passage["maxElevationDeg"]:
            failures.append(f"passage {index} : le sommet annonce n'est pas un maximum "
                            f"local ({before:.4f} / {passage['maxElevationDeg']:.4f} / "
                            f"{after:.4f})")

    # Exhaustivite : Skyfield cherche les passages pour son propre compte.
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
    print(f"\nExhaustivite : Skyfield trouve {complete} passages complets, "
          f"Orekit en a produit {len(reference['passes'])}.")
    if complete != len(reference["passes"]):
        failures.append(f"nombre de passages : Skyfield {complete}, "
                        f"Orekit {len(reference['passes'])}")

    print(f"\nEcart maximal en elevation : {worst_elevation * 1000:.3f} millidegre "
          f"(tolerance {ELEVATION_TOLERANCE_DEG * 1000:.0f})")
    print(f"Ecart maximal en azimut    : {worst_azimuth * 1000:.3f} millidegre "
          f"(tolerance {AZIMUTH_TOLERANCE_DEG * 1000:.0f})")

    if failures:
        print(f"\n{len(failures)} ecart(s) hors tolerance :")
        for failure in failures:
            print(f"  - {failure}")
        return 1

    print("\nOrekit et Skyfield concordent sur l'ensemble de la reference.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
