#!/usr/bin/env bash
# Crée les milestones et les issues de la roadmap sur GitHub.
# Prérequis : gh installé et authentifié (gh auth login). Idempotent-ish :
# relancer créerait des doublons, à n'exécuter qu'une fois.
set -euo pipefail

REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
echo "Dépôt : $REPO"

milestone() { # $1 titre, $2 description, $3 due (YYYY-MM-DD)
  gh api "repos/$REPO/milestones" -f title="$1" -f description="$2" \
     -f due_on="${3}T23:59:59Z" -q .number
}

M1=$(milestone "Jalon 1 — Calculer un passage" "Coeur Orekit : TLEPropagator + ElevationDetector" 2026-10-04)
M2=$(milestone "Jalon 2 — Validation croisée" "Comparaison à une référence externe" 2026-10-11)
M3=$(milestone "Jalon 3 — Récupération des TLE" "Client CelesTrak + cache mémoire" 2026-10-18)
M4=$(milestone "Jalon 4 — API REST" "Endpoint /api/passes + OpenAPI" 2026-10-25)
M5=$(milestone "Jalon 5 — Frontend" "Formulaire et tableau des passages" 2026-11-05)
M6=$(milestone "Jalon 6 — Visualisation" "Diagramme polaire du passage" 2026-11-15)
M7=$(milestone "Jalon 7 — Mise en vitrine" "Docker, README, GIF de démo" 2026-11-22)
M8=$(milestone "Jalon 8 — Différenciation" "Passages visibles, cache PostgreSQL" 2026-12-20)

for L in "orekit:0E8A16:Dynamique du vol et calcul" \
         "backend:1D76DB:Spring Boot" \
         "frontend:5319E7:Angular" \
         "infra:B60205:CI, Docker, outillage" \
         "doc:FBCA04:README et documentation"; do
  IFS=: read -r name color desc <<< "$L"
  gh label create "$name" --color "$color" --description "$desc" --force >/dev/null
done

issue() { # $1 milestone, $2 labels, $3 titre, $4 corps
  gh issue create --milestone "$1" --label "$2" --title "$3" --body "$4" >/dev/null
  echo "  + $3"
}

issue "$M1" orekit "Construire le TopocentricFrame du site d'observation" \
"OneAxisEllipsoid WGS84 sur ITRF + GeodeticPoint (latitude, longitude, altitude) -> TopocentricFrame.

Critère de sortie : test vérifiant que la position du site reprojetée en géodésique redonne les coordonnées d'entrée à moins d'un mètre."

issue "$M1" orekit "Propager un TLE de l'ISS avec TLEPropagator" \
"TLE codé en dur dans une fixture de test, aucun appel réseau.

À savoir expliquer : un TLE s'exprime dans le repère TEME. Documenter la conversion dans le code."

issue "$M1" orekit "Détecter les passages avec ElevationDetector + EventsLogger" \
"Seuil d'élévation 10 degrés, fenêtre de 24 h.

Critère de sortie : liste de passages ordonnés et disjoints, avec AOS, LOS, durée, élévation max et azimuts."

issue "$M2" "orekit" "Figer une fixture de validation (TLE + date)" \
"Choisir un TLE daté et une date d'observation stables, les committer comme ressource de test."

issue "$M2" "orekit,doc" "Comparer les résultats à une référence externe" \
"Source : Heavens-Above ou un calcul indépendant (Skyfield).

Critère de sortie : tolérances choisies ET justifiées dans le test et le README (ex. +/- 30 s sur l'AOS)."

issue "$M3" backend "Client RestClient vers l'API GP de CelesTrak" \
"Récupération par NORAD CATNR. Timeout, satellite inconnu et service indisponible traités explicitement."

issue "$M3" backend "Cache mémoire des TLE (Caffeine, TTL 2 h)" \
"CelesTrak demande de ne pas interroger l'API en boucle. Pas de base de données à ce stade."

issue "$M3" backend "Tests du client avec MockRestServiceServer" \
"Aucun appel réseau réel en CI."

issue "$M4" backend "Endpoint GET /api/passes" \
"Paramètres : noradId, lat, lon, altitude, hours, minElevation.
DTO en Instant ISO-8601 UTC : le fuseau est un problème d'affichage, pas de calcul."

issue "$M4" backend "Validation Jakarta et gestion d'erreurs RFC 9457" \
"@RestControllerAdvice, réponses au format Problem Details."

issue "$M4" "backend,doc" "Documentation OpenAPI (springdoc)" \
"Exposer /swagger-ui et lier la page depuis le README."

issue "$M5" frontend "Formulaire de recherche (signals)" \
"Satellite, position, fenêtre, élévation minimale. Géolocalisation navigateur en option."

issue "$M5" frontend "Tableau des passages" \
"Heure locale, durée, élévation max, azimuts AOS/LOS. États de chargement et d'erreur traités."

issue "$M6" frontend "Diagramme polaire SVG de la trajectoire" \
"Azimut / élévation. Sert d'image de démo dans le README."

issue "$M7" infra "Dockerfile multi-étapes et docker-compose" \
"orekit-data téléchargé au build, pas au runtime."

issue "$M7" doc "README : GIF de démo, schéma d'architecture, badge CI" \
""

issue "$M7" doc "Section Modèle physique du README" \
"Repères TEME / GCRF / ITRF, échelles de temps UTC / TAI / UT1, limites de SGP4 et fenêtre de validité."

issue "$M8" orekit "Passages visibles à l'oeil nu (éclairage et éclipse)" \
"Satellite éclairé par le Soleil ET observateur dans le noir.
C'est la fonctionnalité qui distingue ce dépôt d'un tracker générique."

issue "$M8" backend "Cache des TLE en PostgreSQL" \
"À faire seulement quand le besoin est réel : persistance entre redémarrages, historique."

echo "Terminé."
