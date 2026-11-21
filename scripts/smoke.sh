#!/bin/sh
# Bring a whole gateway up against simulated machines and prod it, from the built jar.
#
# For a commissioning engineer who wants to know the build on the stick works before driving to
# site. It starts the gateway on a temporary directory, walks the endpoints a wall board and a
# handheld use, stops it, and exits nought with "smoke ok" or one with what went wrong.
#
# Usage: scripts/smoke.sh [path/to/nozzlewright.jar]
# Without an argument it uses the installed jar, else the one the build left in target/.
set -eu

here=$(cd "$(dirname "$0")/.." && pwd)

if [ "$#" -gt 0 ]; then
    jar=$1
elif [ -f /opt/nozzlewright/lib/nozzlewright.jar ]; then
    jar=/opt/nozzlewright/lib/nozzlewright.jar
else
    jar=$(ls "$here"/target/nozzlewright-*.jar 2>/dev/null | head -n 1)
fi

if [ -z "${jar:-}" ] || [ ! -f "$jar" ]; then
    echo "smoke: no jar found, build one with ./mvnw package or pass its path" >&2
    exit 1
fi

exec java -cp "$jar" \
    -Dloader.main=io.github.rkaushikdev.nozzlewright.smoke.Smoke \
    org.springframework.boot.loader.launch.PropertiesLauncher
