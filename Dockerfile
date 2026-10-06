FROM maven:3.9.16-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS java-build

WORKDIR /build

# Maven Central intermittently rate-limits CI runners (HTTP 429), and a 429 is cached as a failed
# transfer -- so a plain retry hits the poisoned cache and still fails ("No plugin found for prefix
# 'dependency'"). Each Maven step therefore retries with backoff and forces updates (-U) so the
# cached failures are re-attempted, with connection-level retries inside each run. A brief Central
# hiccup no longer fails the image build; a persistent outage still fails after the last attempt.
ENV MAVEN_NET_OPTS="-ntp -U -Dmaven.wagon.http.retryHandler.count=3 -Dmaven.wagon.http.retryHandler.requestSentEnabled=true"

COPY pom.xml .
RUN for attempt in 1 2 3 4 5; do \
      mvn -B ${MAVEN_NET_OPTS} -DskipTests dependency:go-offline && break; \
      status=$?; \
      [ "$attempt" = 5 ] && { echo "dependency:go-offline failed after $attempt attempts" >&2; exit $status; }; \
      echo "go-offline attempt $attempt failed (exit $status); backing off"; sleep $((attempt * 20)); \
    done
COPY src ./src
RUN for attempt in 1 2 3 4 5; do \
      mvn -B ${MAVEN_NET_OPTS} -Dmaven.test.skip=true package dependency:copy-dependencies \
        -DincludeScope=runtime -DoutputDirectory=target/dependency && break; \
      status=$?; \
      [ "$attempt" = 5 ] && { echo "package failed after $attempt attempts" >&2; exit $status; }; \
      echo "package attempt $attempt failed (exit $status); backing off"; sleep $((attempt * 20)); \
    done

# Pinned by digest so the same commit produces the same image. Security rebases
# arrive through the Dependabot docker updater rather than an implicit tag move.
FROM ubuntu:26.04@sha256:da6fc2be547864451aa253836dd926da33623312df4a9a243e35dc877c378a78

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONPATH=/app

RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jre-headless \
    python3 \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Canonical's base image ships pebble, its service supervisor, as an unpackaged 9 MB Go
# binary. This image's entrypoint is its own script and never invokes it, no dpkg package
# owns it, and nothing in the filesystem references it -- but its vendored Go standard
# library is what every fixable HIGH in the image scan came from. Carrying an unused
# supervisor with its own CVE stream, in a container that already drops all capabilities and
# runs read-only, is attack surface kept for no reason.
RUN rm -f /usr/bin/pebble

RUN groupadd -g 10001 vortex \
    && useradd -u 10001 -g vortex -m -s /bin/bash vortex \
    && install -d -o vortex -g vortex /app /var/lib/vortex

WORKDIR /app
COPY --from=java-build /build/target/classes /app/classes
COPY --from=java-build /build/target/dependency /app/lib
COPY crp_pce /app/crp_pce
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 0555 /app/docker-entrypoint.sh && chown -R vortex:vortex /app

EXPOSE 8080 4189

# The southbound probe is conditional on a listener having been asked for. It used to be
# unconditional, which was correct only while something always opened 4189 -- once DISABLED
# genuinely disabled the port, a plain `docker run` with no listener configured reported
# unhealthy forever while working exactly as configured. A health check that fails on a supported
# configuration trains operators to ignore it.
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD python3 -c "import os,socket,urllib.request; urllib.request.urlopen('http://localhost:8080/livez',timeout=2).read(); os.environ.get('VORTEX_PCEP_LISTENER','DISABLED')=='DISABLED' or socket.create_connection(('127.0.0.1',4189),timeout=2).close()"

USER vortex
ENTRYPOINT ["/app/docker-entrypoint.sh"]
