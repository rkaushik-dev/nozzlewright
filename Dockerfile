# A gateway in a container, for the sites that run one.
#
# Two stages. The first builds a wheel and a virtual environment with nothing
# but the runtime dependencies in it. The second copies that environment onto a
# slim base and nothing else, so the shipped image carries no compiler, no build
# metadata and no test suite.
#
# The image runs as a non root user and writes to exactly one directory, which
# is the same rule the systemd unit follows. A container that has to run as root
# to write its own database is a container somebody will run with --privileged
# the first time a volume mount misbehaves.

FROM python:3.12-slim-bookworm AS build

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /src
COPY pyproject.toml README.md ./
COPY src ./src

RUN python -m venv /opt/nozzlewright \
 && /opt/nozzlewright/bin/pip install --upgrade pip setuptools wheel \
 && /opt/nozzlewright/bin/pip install .

FROM python:3.12-slim-bookworm AS runtime

ENV PATH="/opt/nozzlewright/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    NZW_STATE_DIR=/var/lib/nozzlewright \
    NZW_LOG_FORMAT=json

# The gateway talks to machines over TCP and to nothing else over the network.
# No build tools and no shell utilities beyond what the base carries.
RUN useradd --system --create-home --home-dir /var/lib/nozzlewright --shell /usr/sbin/nologin nozzlewright

COPY --from=build /opt/nozzlewright /opt/nozzlewright

USER nozzlewright
WORKDIR /var/lib/nozzlewright
VOLUME ["/var/lib/nozzlewright"]
EXPOSE 8420

# The schema is brought up before the gateway serves, the same way the unit
# file does it, so a fresh volume comes up working rather than five hundreds.
ENTRYPOINT ["/bin/sh", "-c", "nozzlewright db upgrade && exec nozzlewright serve \"$@\"", "--"]
CMD ["--host", "0.0.0.0", "--port", "8420"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD ["nozzlewright", "doctor"]
