# Sat Pass Predictor

[![CI](https://github.com/warlaxx/sat-pass-predictor/actions/workflows/ci.yml/badge.svg)](https://github.com/warlaxx/sat-pass-predictor/actions/workflows/ci.yml)

Calcul et visualisation des passages de satellites au-dessus d'un point donne.
Backend Java / Spring Boot avec [Orekit](https://www.orekit.org/), frontend Angular.

> Projet d'apprentissage oriente ecosysteme spatial : propagation SGP4 a partir de TLE,
> reperes et echelles de temps, detection d'evenements de visibilite.

## Stack

| Brique    | Choix                                   |
|-----------|-----------------------------------------|
| Backend   | Java 25, Spring Boot 4.1.1, Maven       |
| Dynamique | Orekit 13.1.8                           |
| Frontend  | Angular 22 (standalone, signals, SCSS)  |
| TLE       | API GP de CelesTrak                     |

## Prerequis

- **JDK 25** — Le projet compile en `release 25`, un JDK plus ancien echoue avec
  `release version 25 not supported`.

  ```bash
  brew install openjdk@25
  # Les JDK Homebrew sont keg-only : sans ce lien, /usr/libexec/java_home ne les voit pas.
  sudo ln -sfn /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk \
               /Library/Java/JavaVirtualMachines/openjdk-25.jdk
  export JAVA_HOME=$(/usr/libexec/java_home -v 25)
  ```

- **Maven 3.9+** (`brew install maven`) pour la premiere generation du wrapper.
  Ensuite, `./mvnw` suffit.
- **Node 22 LTS**

## Demarrage

```bash
# 1. Donnees Orekit (sauts de seconde, EOP, ephemerides) — ~100 Mo, non commitees
./scripts/fetch-orekit-data.sh

# 2. Backend (http://localhost:8080)
cd backend && mvn spring-boot:run

# 3. Frontend (http://localhost:4200, /api proxifie vers 8080)
cd frontend && npm install && npm start
```

## Tests

```bash
cd backend  && mvn verify
cd frontend && npm test
```

La validation croisee avec Skyfield est un script separe, volontairement hors de la CI
(voir [Validation](#validation)). Il tourne dans son propre environnement virtuel, pour ne
dependre ni du `python` par defaut du poste ni d'une installation globale :

```bash
python3 -m venv .venv-validation
.venv-validation/bin/pip install skyfield
.venv-validation/bin/python scripts/validate-against-skyfield.py
```

Skyfield exige Python 3. Un `pip install` lance sous un Python 2 encore actif — via pyenv,
par exemple — echoue a la compilation de `sgp4` avec une `SyntaxError` dans son `setup.py` :
le message pointe vers sgp4, la cause est l'interpreteur. `python3 -m pip --version` dit
lequel est reellement utilise.

## Donnees Orekit

Orekit a besoin d'un jeu de donnees externe (historique UTC-TAI, parametres
d'orientation terrestre IERS, modeles de gravite). Sans lui, le premier appel a
`TimeScalesFactory.getUTC()` echoue. Ces fichiers sont volumineux et mis a jour
regulierement : ils ne sont pas commites, mais telecharges par
`scripts/fetch-orekit-data.sh` et charges au demarrage via `OrekitConfig`.
Le chemin est configurable par `OREKIT_DATA_PATH`.

L'application refuse de demarrer si le repertoire est absent : un echec explicite
au demarrage vaut mieux qu'une erreur obscure au premier calcul.

## Validation

Un calcul de mecanique spatiale qui n'est compare a rien n'est pas un calcul, c'est une
opinion. Le projet s'appuie donc sur deux controles distincts, qui ne prouvent pas la meme
chose et dont aucun ne remplace l'autre.

**Un fichier de reference unique.**
`backend/src/test/resources/validation/iss-lyon-reference.json` porte le TLE, l'observateur,
la fenetre et les passages attendus. Il est lu par le test Java *et* par le script Python.
Deux fichiers auraient signifie deux verites, dont l'une aurait pu mentir sans bruit.

**Controle 1 — non-regression (Java, dans la CI).**
`PassPredictionReferenceTest` verifie qu'Orekit reproduit la reference. Il surveille la
derive : une montee de version, un rafraichissement des donnees IERS, une refonte du
service. Il ne dit rien de la justesse : un calcul faux figerait une reference fausse,
que ce test defendrait ensuite fidelement.

**Controle 2 — justesse (Python, hors CI).**
`scripts/validate-against-skyfield.py` confronte la meme reference a
[Skyfield](https://rhodesmill.org/skyfield/), une implementation de SGP4 ecrite en Python,
sans lien de code avec Orekit.

La comparaison naive — demander ses passages a Skyfield et comparer les dates — donne des
ecarts allant jusqu'a une seconde, sans dire lequel des deux a tort : le `find_events` de
Skyfield est documente comme precis a la seconde. Le script procede donc a l'envers : il
prend les dates produites par Orekit et demande a Skyfield **quelle elevation et quel
azimut il calcule a ces instants precis**. Si Orekit a raison, Skyfield doit retrouver
exactement le seuil aux bornes du passage.

Quatre controles : les bornes, le sommet (valeur et caractere de maximum local), les trois
azimuts, et l'exhaustivite — ce dernier etant le seul capable de detecter un passage
*manque* par le pas de detection de 60 s d'Orekit.

### Tolerances retenues, et pourquoi

| Grandeur | Ecart mesure | Tolerance | Marge |
|---|---|---|---|
| Elevation (Orekit vs Skyfield) | 0,53 millidegre | 10 millidegres | x19 |
| Azimut (Orekit vs Skyfield) | 2,0 millidegres | 20 millidegres | x10 |
| Dates (non-regression Java) | 0 | 1 s | — |
| Angles (non-regression Java) | 0 | 0,1 degre | — |

Les tolerances de non-regression ne sont pas des marges d'erreur physiques : elles
absorbent une evolution interne d'Orekit, pas une erreur de modele, qui serait de
plusieurs ordres de grandeur superieure. Reperes utiles : au voisinage de l'AOS, l'ISS
gagne environ 0,1 degre d'elevation par seconde — un ecart d'une seconde et un ecart de
0,1 degre decrivent donc le meme evenement.

### Ce que cette validation ne prouve pas

Skyfield et Orekit implementent **le meme modele**, SGP4. Leur accord etablit que
l'implementation et la chaine de reperes de ce projet sont correctes. Il ne dit rien de
l'ecart au ciel reel, domine par l'age du TLE : en orbite basse, SGP4 derive de l'ordre de
1 a 3 km par jour, davantage pendant une tempete geomagnetique. C'est une limite du modele,
assumee, et la raison pour laquelle la fenetre de prevision est bornee a quelques jours.

Une comparaison a Heavens-Above repondrait a l'autre question. Elle a ete ecartee
volontairement : son ecart melange l'erreur du TLE, la refraction et les conventions
d'affichage du site, et ne serait donc pas interpretable.

### Pourquoi le script Python n'est pas dans la CI

Il exigerait Python et Skyfield dans le workflow pour verifier un fichier qui ne change
pas. La justesse de la reference est etablie une fois ; c'est sa derive qui doit etre
surveillee en continu, et le test Java s'en charge. Le script est a relancer a la main
chaque fois que la reference change — c'est precisement ce que le commentaire en tete du
fichier JSON demande.

## Feuille de route

Detail, jalons et budget temps : [ROADMAP.md](ROADMAP.md).


- [x] Chargement des donnees Orekit, teste
- [x] Calcul des passages (`TLEPropagator` + `ElevationDetector`)
- [x] Validation croisee avec une implementation independante de SGP4 (Skyfield)
- [x] Echantillonnage de la trajectoire (`track`, `OrekitStepHandler`, pas fixe de 10 s)
- [x] Recuperation d'un TLE depuis CelesTrak (dernier TLE connu, age expose)
- [ ] API REST `/api/passes`
- [ ] Frontend : socle, liste des passages, bandeau d'age du TLE
- [ ] Carte du ciel polaire (SVG, sans dependance)
- [ ] Globe 3D : trace au sol, cercle de visibilite, terminateur
- [ ] Docker Compose, mise en vitrine
- [ ] Passages visibles a l'oeil nu, cache des TLE (PostgreSQL)

Maquette d'interface validee : [docs/maquette-interface.html](docs/maquette-interface.html)
(a ouvrir dans un navigateur).

## Methode de travail

Une partie du code de ce depot a ete ecrite avec l'assistance de Claude (Anthropic) : les
commits concernes portent un trailer `Co-Authored-By`. Autant le dire ici plutot que de
laisser le lecteur le decouvrir dans `git log`.

Ce que cela recouvre concretement :

- Le code et les tests ont ete rediges avec assistance, puis **executes et confrontes a
  une reference independante** avant d'etre commites. La section [Validation](#validation)
  decrit la procedure ; elle est reproductible par n'importe qui avec deux commandes.
- Les decisions techniques non triviales sont documentees dans le code, avec leur
  justification et leur contrepartie : le pas de detection de 60 s au lieu des 600 s par
  defaut, le gestionnaire d'evenement `ContinueOnEvent` sans lequel un seul passage serait
  detecte, le maximum d'elevation comme evenement *decroissant* de la derivee, l'exclusion
  des passages tronques par les bords de la fenetre.
- Les limites du modele sont ecrites noir sur blanc plutot que passees sous silence :
  derive de SGP4, absence de refraction sous 5 degres, portee reelle de la validation.

Un outil qui ecrit du code ne dispense pas de savoir le defendre. Cette section existe
pour que ce depot soit juge sur ce qu'il demontre, pas sur ce qu'il dissimule.
