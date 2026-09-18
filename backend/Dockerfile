# Build context is the REPOSITORY ROOT, not backend/:
#   docker build -f backend/Dockerfile -t sat-pass-api .
#
# Two stages. The JDK, Maven and the 100 MB of downloaded archives never reach the
# deployed image, and orekit-data is baked in at build time instead of being fetched
# on every cold start - a cold start that would otherwise fail whenever GitLab is down.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip \
 && rm -rf /var/lib/apt/lists/*

# distributionType=only-script: the wrapper downloads Maven itself, there is no jar to
# copy and none is committed.
COPY backend/mvnw ./mvnw
COPY backend/.mvn ./.mvn
COPY backend/pom.xml ./pom.xml
COPY backend/src ./src

# -DskipTests on purpose. The suite needs orekit-data and takes a minute; CI already
# gates every push on it. An image build that re-runs it tests the same commit twice
# and turns a GitLab outage into a failed deploy.
RUN ./mvnw -B -DskipTests package

# The same archive scripts/fetch-orekit-data.sh uses, so the deployed data set and the
# one the tests ran against come from one place.
RUN curl -fsSL -o /tmp/orekit-data.zip \
      https://gitlab.orekit.org/orekit/orekit-data/-/archive/main/orekit-data-main.zip \
 && unzip -q /tmp/orekit-data.zip -d /tmp \
 && mv /tmp/orekit-data-main /opt/orekit-data \
 && rm /tmp/orekit-data.zip \
 # The same file OrekitDataDirectory checks for. An empty or half-extracted archive
 # fails the build here, where the log is read, rather than the container at boot.
 && test -f /opt/orekit-data/tai-utc.dat


FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN useradd --system --create-home --uid 10001 app

COPY --from=build /opt/orekit-data /app/orekit-data
COPY --from=build /src/target/*.jar /app/app.jar

USER app

# Replaces the whole candidate list of application.yml with one absolute location.
ENV OREKIT_DATA_PATH=/app/orekit-data
# 512 MB of container, and a heap sized as a percentage of it rather than a fixed -Xmx
# that stops being right the day the plan changes. SerialGC: one vCPU, a heap this
# small, and G1's threads cost more than they return.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"

EXPOSE 8080

# Render (and most PaaS) hand the port in $PORT. exec so that java is PID 1 and gets
# SIGTERM directly on shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --server.port=${PORT:-8080}"]
