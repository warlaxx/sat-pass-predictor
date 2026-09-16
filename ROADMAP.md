# Roadmap

Budget réel : **~4 h/semaine**. Chaque jalon est conçu pour tenir dans 1 à 2 semaines,
être poussé seul et être démontrable seul. Aucun jalon ne dépend d'un jalon futur pour
avoir du sens : si le projet s'arrête au jalon 4, ce qui est en ligne reste cohérent.

Cible : version présentable **fin novembre 2026**, mi-décembre avec de la marge.

> Jalons 0 à 2 faits. Il reste ≈ 32 h, soit 8 semaines pleines au rythme de 4 h.
> L'ajout du globe 3D et le découpage plus fin du frontend coûtent une dizaine d'heures
> de plus que la roadmap initiale. C'est un coût assumé, pas un glissement : autant
> l'écrire que le découvrir en décembre.

L'interface a une **maquette validée** (16/09/2026) qui sert de référence pour les
jalons 5 à 7 : `docs/maquette-interface.html`, ouvrable directement dans un navigateur.

---

## ✅ Jalon 0 — Socle (fait)

Monorepo, Spring Boot 4.1.1 / Java 25, Orekit 13.1.8, Angular 22, chargement des
données Orekit testé, CI GitHub Actions.

---

## ✅ Jalon 1 — Calculer un passage (fait)

Le cœur du projet. Tout le reste n'est que de la plomberie autour.

- `TLEPropagator.selectExtrapolator(tle)` à partir d'un TLE de l'ISS **codé en dur**
  (pas d'appel réseau dans les tests).
- Site d'observation : `OneAxisEllipsoid` (WGS84, frame ITRF) + `GeodeticPoint` (Lyon)
  → `TopocentricFrame`.
- `ElevationDetector` (seuil 10°) + `EventsLogger` sur une propagation de 24 h.
- Sortie : une liste de `SatellitePass` (AOS, LOS, durée, élévation max, azimuts).

**Piège connu** : un TLE s'exprime dans le repère **TEME**, pas dans GCRF ni ITRF.
Orekit gère la conversion, mais tu dois savoir l'expliquer — c'est une question
d'entretien quasi certaine.

---

## ✅ Jalon 2 — Validation croisée (fait)

Comparaison à Skyfield, implémentation Python indépendante de SGP4. Tolérances retenues,
justifiées, et écart résiduel expliqué dans la section « Validation » du README. Script
reproductible dans `scripts/`.

C'est ce jalon qui sépare ce dépôt des centaines de clones — et il est fait avant le
reste, pas à la fin.

**Reste ouvert** : Skyfield et Orekit implémentent *le même modèle*. Leur accord prouve
que l'implémentation et la chaîne de repères sont correctes ; il ne dit rien de l'écart
au ciel réel, dominé par l'âge du TLE. Une comparaison à Heavens-Above répondrait à
l'autre question. Les passages de référence pour Lyon (16–25 septembre 2026) sont déjà
relevés dans `docs/maquette-interface.html` si tu veux la mener.

---

## ✅ Jalon 3 — Échantillonnage de la trajectoire (fait)

`SatellitePass` exposait trois instants (AOS, culmination, LOS). Les deux vues de
l'interface ont besoin d'une **polyligne**, pas de trois points. C'est fait avant que
l'API ne soit publiée, pour ne pas avoir à reprendre ensuite le service, le DTO, les
tests et un contrat déjà en ligne.

- `TrackPoint(instant, azimuthDeg, elevationDeg, rangeKm, subPoint, illuminated)` et
  `List<TrackPoint> track` dans `SatellitePass`.
- `SubSatellitePoint(latitudeDeg, longitudeDeg, altitudeKm)` — **type du domaine, pas le
  `GeodeticPoint` d'Orekit**. Écart assumé par rapport au plan initial : le domaine
  n'importe pas Orekit, et ce point part tel quel dans le JSON du jalon 5 puis dans le
  globe du jalon 8. Exposer le type d'Orekit aurait fait de sa sérialisation — angles en
  radians, champs dérivés — un contrat public involontaire.
- Calcul en **deux passes** : une propagation sur toute la fenêtre pour les bornes
  (détecteurs d'événements, recherche de racine, précision à la milliseconde), puis une
  propagation par passage sur [AOS, LOS] avec un `OrekitStepHandler` qui prélève les
  échantillons *à l'intérieur* des pas d'intégration. Jamais un `propagate()` par point.
- `subPoint` par projection ITRF côté Orekit. **Jamais recalculé côté navigateur.**
- `illuminated` renvoyé à `false` jusqu'au jalon 10.

**Décision : pas fixe de 10 s**, et non nombre de points fixe par passage. Les instants
tombent sur des multiples ronds depuis l'AOS, donc directement lisibles comme étiquettes
horaires sur la carte du ciel ; la densité de points dit quelque chose de vrai (un
passage long a plus de points parce qu'il dure plus longtemps) ; et la règle tient en une
phrase, ce que « 60 points » ne fait pas — il faudrait expliquer pourquoi 60.
Contrepartie assumée : un passage rasant de 50 s ne donne que 4 points intermédiaires et
sa courbe est visiblement anguleuse.

**AOS, sommet et LOS ne sont pas interpolés** : ils sont construits depuis les
`SpacecraftState` déjà produits par la recherche de racine, puis insérés dans la
polyligne. Conséquence visible : le marqueur du sommet tombe *sur* la courbe. Sans cela
il flotterait à côté — près du zénith l'ISS gagne plusieurs degrés d'élévation en
quelques secondes, et le sommet ne tombe jamais sur un multiple de 10 s.

**Critère de sortie atteint** : `TrackSamplingTest` vérifie que le premier et le dernier
point coïncident avec l'AOS et le LOS et que l'élévation y vaut le seuil à 1e-3 degré
près, que le sommet figure dans la trajectoire, que les points sont chronologiques et
espacés d'au plus 10 s, et que chaque point sous-satellite est plausible pour une orbite
basse inclinée à 51,6°.

---

## ✅ Jalon 4 — Récupération des TLE (fait)

Un TLE figé suffisait pour valider le calcul ; il ne suffit pas pour une application.
CelesTrak republie les éléments de l'ISS plusieurs fois par jour, et c'est leur âge —
pas le modèle — qui domine l'écart au ciel réel.

- `TleSnapshot(noradId, name, line1, line2, epoch, fetchedAt, source)` dans le domaine.
- `CelestrakTleClient` : `RestClient` vers l'API GP (`CATNR`, `FORMAT=TLE`), timeouts
  explicites, validation par Orekit **à la récupération**.
- `TleStore` : magasin borné du dernier TLE connu par satellite.
- 15 tests, aucun appel réseau (`MockRestServiceServer`, horloge injectée).

**Décision : ce n'est pas un cache à TTL.** La consigne initiale disait « Caffeine,
TTL 2 h » et exigeait deux lignes plus bas qu'un CelesTrak injoignable n'empêche pas
l'application de fonctionner. Les deux ne tiennent pas ensemble : avec une expiration à
2 h, la première requête arrivée à 2 h 01 pendant une panne ne trouve plus rien. D'où
l'inversion — **rien n'expire** ; les 2 h déclenchent une *tentative* de
rafraîchissement, et son échec laisse le snapshot précédent en place avec son âge réel.
Caffeine sert de magasin borné (`maximumSize`), pas de cache. Contrepartie assumée :
c'est un écart explicite à la consigne, et il faut donc savoir l'expliquer.

**Deux âges, jamais confondus.** L'âge *depuis l'époque* est physique : l'erreur de SGP4
croît avec lui, de l'ordre du kilomètre par jour en orbite basse, et c'est lui qu'affiche
le bandeau d'incertitude. L'âge *depuis la récupération* est opérationnel : il décide
seulement s'il faut rappeler CelesTrak. Un cache qui expire au bout de deux heures croit
garantir une précision qu'il ne contrôle pas.

**Deux limites à la dégradation.** Au-delà de `tle.max-age` (7 jours d'époque), la
prédiction est refusée plutôt qu'affichée au degré près — de la fausse précision. Et un
satellite absent du catalogue (`TleNotFoundException`) n'est **jamais** dégradé : il est
oublié du magasin. Un objet qui disparaît de CelesTrak est le plus souvent rentré dans
l'atmosphère, et propager son dernier TLE afficherait les passages d'un satellite qui
n'existe plus.

**Deux pièges de l'API GP**, tous deux encodés dans les tests : un numéro NORAD inconnu
répond **200 avec le corps `No GP data found`**, pas 404 ; et sous charge CelesTrak sert
une page HTML, toujours en 200. Tout corps qui ne ressemble pas à un TLE est donc traité
comme une panne. Le numéro renvoyé est en outre vérifié contre celui demandé : sans ce
contrôle, une réponse mise en cache par un intermédiaire pour un autre satellite
produirait des passages parfaitement plausibles — et faux.

**Un seul appel réseau par satellite.** Le rafraîchissement passe par
`asMap().compute(...)`, atomique par clé chez Caffeine : dix requêtes simultanées sur
l'ISS donnent un appel, pas dix, ce que la documentation de CelesTrak demande
explicitement. Contrepartie assumée : l'appel réseau a lieu sous le verrou de la clé —
borné par les timeouts, et seuls les appelants du *même* satellite attendent.

**Découpage des contextes de test, fait dans la foulée.** Le premier `verify` de ce
jalon a fait échouer 26 tests sur cinq classes dont aucune ne touche au réseau : toutes
étaient en `@SpringBootTest` nu, donc toutes démarraient l'application entière, donc
toutes tombaient avec le bean HTTP mal câblé. Un test doit échouer pour ce qu'il teste.
Elles passent désormais par `@OrekitTest`, une tranche nommant `OrekitConfig` et
`PassPredictionService` — un seul contexte, mis en cache, sans couche web.

La contrepartie est réelle : plus rien ne vérifiait alors que l'application *réelle*
démarre, or c'est exactement le défaut qui venait de passer à travers. D'où
`ApplicationStartupTest`, seul test à tout démarrer, qui vérifie en plus que les beans
porteurs de comportement sont présents — un contexte peut démarrer en ayant silencieusement
omis un `@Component`. Un test démarre tout et échoue seul ; les autres restent lisibles.

Corollaire découvert au passage : `DataContext.getDefault()` est un singleton de JVM, pas
un bean. Avec deux contextes Spring dans la même JVM, `addProvider` empilait deux
fournisseurs sur les mêmes fichiers EOP. `OrekitConfig` fait maintenant
`clearProviders()` d'abord — l'enregistrement est idempotent.

**Critère de sortie atteint** : `CelestrakTleClientTest` couvre la réponse nominale,
`No GP data found`, une page HTML, un mauvais numéro NORAD, une somme de contrôle
altérée, un timeout et un 500. `TleStoreTest` couvre la fenêtre de rafraîchissement, le
repli sur le dernier TLE connu, l'absence de repli au premier appel, l'oubli d'un
satellite retiré du catalogue, la limite d'âge dure et la fusion des appels concurrents.

---

## Jalon 5 — API REST (≈ 4 h · 1 semaine)

- `GET /api/passes?noradId=25544&lat=45.75&lon=4.85&altitude=200&hours=48&minElevation=10`
- DTO en `Instant` ISO-8601 UTC. Le fuseau est un problème d'affichage, pas de calcul.
- La réponse porte `tle` (époque, âge, source, date de récupération), `observer`,
  `minElevationDeg` et la liste des passages avec leur `track`.
- Validation Jakarta + `@RestControllerAdvice` (erreurs au format Problem Details, RFC 9457).
- Tests `@WebMvcTest`, documentation springdoc-openapi.

**Critère de sortie** : le JSON documenté dans `docs/maquette-interface.html`
(section « Ce que l'API doit renvoyer ») est servi tel quel.

---

## Jalon 6 — Frontend : socle et liste (≈ 6 h · 1,5 semaine)

Référence visuelle : `docs/maquette-interface.html`. **La maquette est la cible visuelle
et comportementale, pas un gabarit à copier** : le DOM y est construit en JavaScript
impératif, ce qui n'a pas sa place dans un composant Angular.

- Jetons de design dans `styles.scss` (variables CSS) : palette sombre unique, échelle
  de couleur par élévation, trois rôles typographiques IBM Plex. Toute donnée numérique
  en `IBM Plex Mono` avec `font-variant-numeric: tabular-nums`.
- Composants `standalone`, `OnPush`, état par **signals**, application **zoneless**.
- `httpResource` pour l'appel API, avec ses trois états réellement dessinés
  (chargement / erreur / vide) — pas de spinner infini.
- Formulaire : satellite, position, fenêtre, élévation minimale. Géolocalisation
  navigateur en option, saisie manuelle toujours possible.
- `TleBannerComponent` : époque, âge, dérive attendue, incertitude sur l'AOS.
- `PassTableComponent` : heure locale **et** UTC, durée, élévation max, azimuts en
  cardinaux. Lignes focusables et activables au clavier.
- `PassRibbonComponent` : une barre par passage, hauteur = élévation maximale.

---

## Jalon 7 — Carte du ciel (≈ 5 h · 1,5 semaine)

La vue qui répond à « où lever les yeux depuis Lyon ». **Aucune dépendance externe** :
SVG rendu par le template Angular à partir de `computed()`.

- Disque polaire : bord = horizon, centre = zénith, nord en haut. Cercles à 30° et 60°,
  cercle pointillé au seuil de 10°, cardinaux à l'extérieur.
- Trajectoire tracée depuis `track` : trait plein tant que le satellite est éclairé,
  pointillé ensuite. Repères et étiquettes horaires toutes les minutes.
- `TransportBarComponent` : une **horloge unique** pour toute la page,
  `t ∈ [0, 2]` porté par un signal, lecture / pause / curseur, et lecture continue de
  l'heure, de l'azimut, de l'élévation, de la distance et de l'état d'éclairement.
- `requestAnimationFrame`, jamais `setInterval`. Respect de `prefers-reduced-motion`.
- Équivalent textuel accessible : le tableau des passages, avec un `<caption>` qui le dit.

C'est l'image qui sert de GIF de démo dans le README. Elle vaut plus que trois
paragraphes de description.

---

## Jalon 8 — Globe 3D (≈ 6 h · 2 semaines)

La vue qui répond à « où est l'ISS, et qui d'autre la voit ». Complémentaire de la carte
du ciel : l'une est en repère topocentrique, l'autre en repère terrestre.

**Condition non négociable : le globe ne calcule rien.** Il rend ce que l'API renvoie.
Aucune propagation côté navigateur, aucun `satellite.js` — sinon la question « qui fait
le calcul faisant autorité ? » ruine tout le projet.

- three.js (r128, UMD). Sphère, trait de côte Natural Earth 110 m, graticule, halo.
- Éclairage par une `DirectionalLight` à la direction du Soleil → le **terminateur
  jour/nuit** apparaît tout seul, et explique visuellement pourquoi le passage est visible.
- Trace au sol depuis `subPoint`, découpée en portion éclairée et portion dans l'ombre.
- **Cercle de visibilité** autour du point sous-satellite :
  `acos(Re/(Re+h)·cos(10°)) − 10°` ≈ 12,5°, soit ~1 390 km.
- Marqueur de l'observateur, ligne de visée pendant le passage, rotation à la souris
  (pointer events — `OrbitControls` n'est pas dans le bundle UMD).
- **Repli obligatoire** si three.js ne charge pas : message explicite dans le cadre, et
  le reste de la page reste utilisable. C'est précisément pour ça que la carte du ciel
  n'a aucune dépendance.

---

## Jalon 9 — Mise en vitrine (≈ 4 h · 1 semaine)

- Dockerfile multi-étapes + `docker-compose.yml` (téléchargement d'`orekit-data`
  au build, pas au runtime).
- README : GIF de démo, schéma d'architecture, badge CI.
- Section « Modèle physique » : repères (TEME / GCRF / ITRF), échelles de temps
  (UTC / TAI / UT1), limites de SGP4, rôle des EOP.

---

## Jalon 10 — Différenciation (optionnel, mais c'est là qu'est la valeur)

- **Passages visibles à l'œil nu** : satellite éclairé par le Soleil + observateur
  dans le noir. Nécessite la position du Soleil et la détection d'éclipse. C'est
  la fonctionnalité qui transforme « encore un tracker » en « quelqu'un qui a
  compris la dynamique ».
  Le champ `illuminated` existe depuis le jalon 3 ; ici il cesse de valoir `false`,
  et les deux vues s'allument sans changer une ligne de frontend.
- Cache des TLE en PostgreSQL — à ce stade seulement, quand le besoin est réel.
- Plusieurs satellites, prochaine fenêtre favorable sur 7 jours.

---

## Limites assumées

Ces choix sont volontaires et doivent être défendus, pas cachés :

- **SGP4 uniquement.** Le modèle dérive au-delà de quelques jours ; la fenêtre de
  prévision est donc bornée. C'est la bonne réponse pour des TLE, pas une limite subie.
- **Pas de réfraction atmosphérique** sous 5° d'élévation au MVP.
- **Pas de base de données** tant qu'un besoin réel ne l'impose pas.
- **Thème sombre unique** au frontend : l'usage réel est nocturne. C'est un choix, pas
  une économie de travail.
- **Le frontend ne calcule aucune orbite.** Ni la carte du ciel, ni le globe.
