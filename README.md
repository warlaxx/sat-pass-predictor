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

## Donnees Orekit

Orekit a besoin d'un jeu de donnees externe (historique UTC-TAI, parametres
d'orientation terrestre IERS, modeles de gravite). Sans lui, le premier appel a
`TimeScalesFactory.getUTC()` echoue. Ces fichiers sont volumineux et mis a jour
regulierement : ils ne sont pas commites, mais telecharges par
`scripts/fetch-orekit-data.sh` et charges au demarrage via `OrekitConfig`.
Le chemin est configurable par `OREKIT_DATA_PATH`.

L'application refuse de demarrer si le repertoire est absent : un echec explicite
au demarrage vaut mieux qu'une erreur obscure au premier calcul.

## Feuille de route

Detail, jalons et budget temps : [ROADMAP.md](ROADMAP.md).


- [x] Chargement des donnees Orekit, teste
- [ ] Recuperation d'un TLE depuis CelesTrak
- [ ] Calcul des passages (`TLEPropagator` + `ElevationDetector`)
- [ ] API REST `/api/passes`
- [ ] Affichage frontend (liste + carte)
- [ ] Validation croisee avec une reference externe (Heavens-Above)
- [ ] Cache des TLE (PostgreSQL), Docker Compose
