# A gateway in a container, for the sites that run one.
#
# Two stages. The first builds the runnable jar with the Maven wrapper. The second copies that jar
# and its launcher onto a Java runtime and nothing else, so the shipped image carries no compiler,
# no build tooling and no test suite.
#
# The image runs as a non root user and writes to exactly one directory, which is the same rule the
# systemd unit follows. A container that has to run as root to write its own database is a
# container somebody will run with --privileged the first time a volume mount misbehaves.

FROM eclipse-temurin:21-jdk AS build

WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src ./src
RUN ./mvnw -B -q -DskipTests package \
 && cp target/nozzlewright-*.jar /src/nozzlewright.jar

FROM eclipse-temurin:21-jre AS runtime

ENV NZW_STATE_DIR=/var/lib/nozzlewright \
    NZW_LOG_FORMAT=json \
    PATH="/opt/nozzlewright/bin:$PATH"

# The gateway talks to machines over TCP and to nothing else over the network.
RUN useradd --system --create-home --home-dir /var/lib/nozzlewright --shell /usr/sbin/nologin nozzlewright

COPY --from=build /src/nozzlewright.jar /opt/nozzlewright/lib/nozzlewright.jar
COPY --from=build /src/src/main/dist/bin/nozzlewright /opt/nozzlewright/bin/nozzlewright

USER nozzlewright
WORKDIR /var/lib/nozzlewright
VOLUME ["/var/lib/nozzlewright"]
EXPOSE 8420

# The schema is brought up before the gateway serves, the same way the unit file does it, so a
# fresh volume comes up working rather than answering with server errors.
ENTRYPOINT ["/bin/sh", "-c", "nozzlewright db upgrade && exec nozzlewright serve \"$@\"", "--"]
CMD ["--host", "0.0.0.0", "--port", "8420"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD ["nozzlewright", "doctor"]
