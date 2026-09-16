# Roadmap

Budget réel : **~4 h/semaine**. Chaque jalon est conçu pour tenir dans 1 à 2 semaines,
être poussé seul et être démontrable seul. Aucun jalon ne dépend d'un jalon futur pour
avoir du sens : si le projet s'arrête au jalon 4, ce qui est en ligne reste cohérent.

Cible : version présentable fin novembre 2026.

---

## ✅ Jalon 0 — Socle (fait)

Monorepo, Spring Boot 4.1.1 / Java 25, Orekit 13.1.8, Angular 22, chargement des
données Orekit testé, CI GitHub Actions.

---

## Jalon 1 — Calculer un passage (≈ 6 h · 2 semaines)

Le cœur du projet. Tout le reste n'est que de la plomberie autour.

- `TLEPropagator.selectExtrapolator(tle)` à partir d'un TLE de l'ISS **codé en dur**
  (pas d'appel réseau dans les tests).
- Site d'observation : `OneAxisEllipsoid` (WGS84, frame ITRF) + `GeodeticPoint` (Lyon)
  → `TopocentricFrame`.
- `ElevationDetector` (seuil 10°) + `EventsLogger` sur une propagation de 24 h.
- Sortie : une liste de `SatellitePass` (AOS, LOS, durée, élévation max, azimuts).

**Critère de sortie** : un test vert qui produit un nombre plausible de passages
sur 24 h et dont les heures sont ordonnées et disjointes.

**Piège connu** : un TLE s'exprime dans le repère **TEME**, pas dans GCRF ni ITRF.
Orekit gère la conversion, mais tu dois savoir l'expliquer — c'est une question
d'entretien quasi certaine.

---

## Jalon 2 — Validation croisée (≈ 4 h · 1 semaine)

**À faire maintenant, pas à la fin.** Si la physique est fausse, tout ce qui suit
n'est que du vernis sur une erreur.

- Figer un TLE daté + une date d'observation comme fixture de test.
- Comparer AOS / LOS / élévation max à une source externe (Heavens-Above, ou un
  calcul indépendant avec Skyfield en Python).
- Écrire la tolérance retenue **et sa justification** (ex. ±30 s sur l'AOS, ±1° sur
  l'élévation max) dans le test et dans le README.

**Critère de sortie** : un test de non-régression documenté, et une section README
qui explique l'écart résiduel. C'est ce jalon qui sépare ton dépôt des centaines
de clones.

---

## Jalon 3 — Récupération des TLE (≈ 4 h · 1 semaine)

- Client `RestClient` vers l'API GP de CelesTrak (`CATNR`, format TLE).
- Cache mémoire (Caffeine, TTL 2 h) — CelesTrak demande explicitement de ne pas
  interroger l'API en boucle. **Toujours pas de base de données.**
- Gestion des pannes : timeout, satellite inconnu, service indisponible.
- Tests avec `MockRestServiceServer`, jamais d'appel réseau réel en CI.

---

## Jalon 4 — API REST (≈ 4 h · 1 semaine)

- `GET /api/passes?noradId=25544&lat=45.75&lon=4.85&altitude=200&hours=48&minElevation=10`
- DTO en `Instant` ISO-8601 UTC. Le fuseau est un problème d'affichage, pas de calcul.
- Validation Jakarta + `@RestControllerAdvice` (erreurs au format Problem Details, RFC 9457).
- Tests `@WebMvcTest`, documentation springdoc-openapi.

---

## Jalon 5 — Frontend (≈ 6 h · 1,5 semaine)

- Formulaire (signals) : satellite, position, fenêtre, élévation minimale.
- Géolocalisation navigateur en option.
- Tableau des passages : heure locale, durée, élévation max, azimuts.
- États de chargement et d'erreur traités — pas de spinner infini.

---

## Jalon 6 — Visualisation (≈ 5 h · 1,5 semaine)

- Diagramme polaire SVG de la trajectoire du passage (azimut / élévation).
- C'est l'image qui sert de GIF de démo dans le README. Elle vaut plus que trois
  paragraphes de description.

---

## Jalon 7 — Mise en vitrine (≈ 4 h · 1 semaine)

- Dockerfile multi-étapes + `docker-compose.yml` (téléchargement d'`orekit-data`
  au build, pas au runtime).
- README : GIF de démo, schéma d'architecture, badge CI.
- Section « Modèle physique » : repères (TEME / GCRF / ITRF), échelles de temps
  (UTC / TAI / UT1), limites de SGP4.

---

## Jalon 8 — Différenciation (optionnel, mais c'est là qu'est la valeur)

- **Passages visibles à l'œil nu** : satellite éclairé par le Soleil + observateur
  dans le noir. Nécessite la position du Soleil et la détection d'éclipse. C'est
  la fonctionnalité qui transforme « encore un tracker » en « quelqu'un qui a
  compris la dynamique ».
- Cache des TLE en PostgreSQL — à ce stade seulement, quand le besoin est réel.
- Plusieurs satellites, prochaine fenêtre favorable sur 7 jours.

---

## Limites assumées

Ces choix sont volontaires et doivent être défendus, pas cachés :

- **SGP4 uniquement.** Le modèle dérive au-delà de quelques jours ; la fenêtre de
  prévision est donc bornée. C'est la bonne réponse pour des TLE, pas une limite subie.
- **Pas de réfraction atmosphérique** sous 5° d'élévation au MVP.
- **Pas de base de données** tant qu'un besoin réel ne l'impose pas.
