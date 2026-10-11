# ION 4.2.1 (ION-OPEN-SOURCE-4.2.1-a.1) at the exact commit behind the paper, built from source with
# the source tree kept in /opt/ION-DTN so the patched image below can rebuild one daemon.
#   docker build -t ion-dtn:paper -f reproduce/ascend2027/ion/Dockerfile reproduce/ascend2027/ion
FROM debian:12
ARG ION_COMMIT=58eb09f7532a61986a4ad277d43a398cb3e6af94
RUN apt-get update && apt-get install -y --no-install-recommends \
    git build-essential automake libtool autoconf pkg-config ca-certificates procps \
    && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /opt/ION-DTN && cd /opt/ION-DTN && git init -q \
    && git remote add origin https://github.com/nasa-jpl/ION-DTN.git \
    && git fetch -q --depth 1 origin "$ION_COMMIT" && git checkout -q FETCH_HEAD \
    && test "$(git rev-parse HEAD)" = "$ION_COMMIT" \
    && autoreconf -fi && ./configure && make -j4 && make install && ldconfig
WORKDIR /opt/ION-DTN
