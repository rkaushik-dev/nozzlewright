# nozzlewright

[![CI](https://github.com/rkaushik-dev/nozzlewright/actions/workflows/ci.yml/badge.svg)](https://github.com/rkaushik-dev/nozzlewright/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-007396)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)
![Maven](https://img.shields.io/badge/build-Maven-C71A36)
![JUnit 5](https://img.shields.io/badge/tests-JUnit%205-25A162)

Fleet control for Halvex thermal inkjet coding printers: the HVX4PEN and
HVX8PEN heads, and the HVXDOD and HVXLCP machines that speak the same line protocol.

A Halvex machine puts a date code, a batch number and usually a barcode onto
product as it goes past on a conveyor. A plant has between two and forty of
them, they are configured one at a time through a four line screen on the front,
and nothing on site knows what any of them is doing until somebody walks over
and looks. This is the software that fixes that.

Two programs, one codebase. `nozzlewright` is a command line tool a technician runs from
a laptop or a handheld, over TCP or Bluetooth RFCOMM. `nozzlewright serve` is a gateway
that sits in a cabinet, polls every machine on the site, writes down what it
sees, and serves an HTTP API and a wall board.

## What it does

**Talks the protocol properly.** `VERB:arg,arg` and a CRLF, responses as
`status:payload`, one command in flight at a time because the firmware cannot do
better, a keepalive against the three second watchdog, and an XOR checksum on
the builds that want one. Every verb is in a catalogue with its permission
level, its firmware revision and the families that carry it, so a command that
is going to be refused is refused on the laptop.

**Knows what a label is.** Labels are stored as source text and parsed into
elements and fields: text, Code 128, Code 39, rules, and the dynamic fields a
coder needs, which are counters, date codes, expiry offsets, shift codes and
operator prompts. A label is rendered to the dot matrix the head actually fires,
so the width, the dot count and the ink per thousand are measured rather than
estimated.

**Watches the site.** One poll loop per machine, backing off on a machine that
has stopped answering and chasing one that is in alarm. Readings, alarms, print
runs and configuration snapshots go into the gateway's database, with a
different retention per table and a janitor that enforces it.

**Says what is wrong before somebody phones.** A nozzle test decoded into which
dot rows will come out blank, and a message checked against them, so the answer
to whether the next carton will scan is arithmetic rather than opinion. Head
recovery that purges what a purge can fix and says when a cartridge is finished.
Machine clocks compared against the gateway, because every date on a carton
comes off one. Profile drift, so the setting somebody nudged at two in the
morning is found before the machine is swapped.

**Costs it.** Cost per thousand codes for a message, out of its dot count, the
drop volume and what a cartridge costs, with the purges counted in.

## Technology

- Java 21, built with Maven (the wrapper is checked in: `./mvnw`).
- Spring Boot 3.5 for the gateway: Spring Web MVC for the HTTP API, springdoc for the
  OpenAPI document, Spring Data JPA with Hibernate over an embedded H2 database, and
  Flyway for the schema migrations.
- picocli for the command line. Commands other than `serve` never start Spring.
- SLF4J and Logback for logging, as short console lines or as JSON for a collector,
  with operator PINs blanked inside the logging pipeline.
- JUnit 5 and AssertJ for the tests; Spotless (palantir-java-format) and a JaCoCo line
  coverage gate run as part of the build.

## Building and testing

```
./mvnw -B verify
```

compiles with every lint warning treated as an error, checks the formatting, runs the
whole suite and fails below 80 percent line coverage. `./mvnw spotless:apply` fixes the
formatting. There is no test that reaches a real printer: the suite talks to the machine
model in `simulator` over an in process link.

The build produces `target/nozzlewright-0.1.0.jar`, a runnable jar holding both programs.
`src/main/dist/bin/nozzlewright` is the launcher an installed box puts in
`/opt/nozzlewright/bin`, next to the jar in `/opt/nozzlewright/lib/nozzlewright.jar`.

## Getting started

```
./mvnw -B -DskipTests package
alias nozzlewright='java -jar target/nozzlewright-0.1.0.jar'
nozzlewright --state-dir ./state db upgrade
nozzlewright --state-dir ./state --simulate registry add line4 10.4.0.21:9100 --line 4
nozzlewright --state-dir ./state --simulate printer status line4
```

`--simulate` swaps the real link for the machine model in `simulator`, which answers the
whole catalogue. Everything below works against it, including the gateway, which is how the
demo on a training laptop runs.

Serving a site:

```
nozzlewright --state-dir ./state serve --host 0.0.0.0 --port 8420
```

The wall board is at `/board` and needs no key. Everything else is under an API
key, sent as the `X-NZW-Key` header, when the site has any. `nozzlewright install` writes
the systemd unit and its environment file for a real box, and there is a `Dockerfile` for
sites that run containers: it builds the jar, installs it under `/opt/nozzlewright`,
upgrades the schema and serves on port 8420 as a non root user that writes only to
`/var/lib/nozzlewright`.

## The command line

```
nozzlewright [--state-dir DIR] [--user NAME --pin PIN] [--log-level LEVEL] [--simulate] COMMAND
```

| command | what it does |
| --- | --- |
| `registry list\|add\|remove\|enable` | the machines on this site |
| `printer status\|ink\|can\|clock\|nozzles\|verify\|recover\|config\|apply\|start\|stop\|pause\|select\|labels` | talk to one machine |
| `label check\|proof\|cost\|format\|list\|save\|diff\|stored-diff\|show\|delete` | work on labels without a machine |
| `fleet printers\|alarms\|ack\|runs\|report\|push\|cost\|export` | read what the site has been doing |
| `profile list\|show\|capture\|delete\|plan\|drift\|rollout` | named configurations and rolling them out |
| `serve` | run the gateway |
| `db upgrade\|status\|prune` | database chores |
| `key new\|hash\|check` | make and hash API keys |
| `install` | write the service unit and environment file for this site |
| `doctor` | check whether this box is fit to run a site |
| `version` | print what is running here |

Output is plain `key=value` text by default and JSON with `--json`. A refused command
prints why on standard error and exits 1; a command line that does not parse exits 2.

Settings come from the environment: `NZW_SITE_ID`, `NZW_STATE_DIR`, `NZW_LOG_LEVEL`,
`NZW_LOG_FORMAT` (`console` or `json`), and the `NZW_LINK_*`, `NZW_SESSION_*` and
`NZW_NOTIFY_*` groups (for example `NZW_LINK_TCP_PORT`, `NZW_SESSION_COMMAND_TIMEOUT_S`,
`NZW_NOTIFY_WEBHOOK_URL`). A list such as `NZW_LINK_RECONNECT_BACKOFF_S` is written comma
separated.

## The site file

One TOML file at `<state-dir>/site.toml` carries what differs between plants:
the site name, the shift pattern, where alarm notices go, how long history is
kept, what a cartridge costs, the API keys as digests, and the machines.
Environment variables beat the file and the file beats the defaults.

```toml
[site]
id = "bremen-2"

[[shifts]]
code = "A"
start = 06:00:00
end = 14:00:00

[retention]
samples = 14
runs = 180

[api]
keys = ["sha256:..."]     # nozzlewright key new

[[printers]]
id = "line4"
endpoint = "10.4.0.21:9100"
line = "4"
tags = ["carton"]
```

The gateway keeps its database in `<state-dir>/site.mv.db` (H2) and upgrades its schema
on start. `nozzlewright db status` says which revision a database is at.

## The HTTP API

The OpenAPI document is at `/openapi.json` and the interactive documentation at `/docs`.

| method | path | what it answers |
| --- | --- | --- |
| GET | `/health` | whether the gateway is up, and its build (no key) |
| GET | `/board` | the wall board page (no key) |
| GET | `/metrics` | Prometheus metrics in the text exposition format (no key) |
| GET | `/fleet/status` | the latest reading of every machine |
| GET | `/fleet/summary` | the site rollup |
| GET | `/fleet/alarms` | open alarms, paged |
| POST | `/fleet/alarms/{printer_id}/{name}/ack` | acknowledge one alarm |
| GET, POST | `/printers` | list the machines, register one |
| GET, DELETE | `/printers/{printer_id}` | one machine, take it off the site |
| GET | `/printers/{printer_id}/status` | its status, read now |
| GET | `/printers/{printer_id}/ink` | its cartridges |
| GET | `/printers/{printer_id}/nozzles` | a nozzle test, run now |
| POST | `/printers/{printer_id}/recover` | head recovery |
| GET, POST | `/printers/{printer_id}/config` | read its configuration, apply one |
| POST | `/printers/{printer_id}/print/start` | start printing |
| POST | `/printers/{printer_id}/print/stop` | stop printing |
| POST | `/printers/{printer_id}/label/{name}` | select a label |
| GET | `/labels` | the stored labels |
| GET, PUT | `/labels/{name}` | one label, store a new revision |
| GET | `/labels/{name}/revisions` | its revisions, paged |
| GET | `/labels/{name}/diff` | what changed between two revisions |
| GET | `/history/samples/{printer_id}` | readings |
| GET | `/history/runs/{printer_id}` | print runs |
| GET | `/history/alarms/{printer_id}` | alarm history |
| GET | `/history/audit` | the audit trail |
| GET | `/ops/doctor` | the box's own health checks |
| GET | `/ops/capabilities` | which machines can do what |
| GET | `/ops/upgrade/{family}` | what a firmware upgrade would add |
| GET | `/ops/storage` | database size and retention |

Errors come back as `{"error": "...", "detail": "...", "printer_id": "..."}` with a status
that says what kind of failure it was: 401 or 403 for keys and operator levels, 409 for a
machine that is mid run, 502 when the machine refused, 504 when it did not answer.

## Layout

| package | what is in it |
| --- | --- |
| `protocol` | the catalogue, framing, the codec, dialects, and what each family can do |
| `transport` | TCP, RFCOMM and a loopback for the simulator |
| `session` | the command channel, login, the supervisor, and the shared link pool |
| `printer` | geometry, firing, the encoder, cartridges, status, nozzles, the clock |
| `labels` | parsing, fonts, barcodes, rastering, linting, diffing and verification |
| `fleet` | the registry, the poller, shifts, reports, OEE, costing, notices, drift |
| `storage` | the entities, the repositories, migrations, the recorder and the janitor |
| `api` | the HTTP surface, the wall board and the key ring |
| `cli` | everything a person types |
| `gateway` | the poller, recorder, janitor, notices and API wired into one service |
| `doctor` | the checks that say whether a box is fit to run a site |
| `simulator` | a machine that answers the catalogue, for tests and for training |
| `smoke` | brings a whole gateway up against simulated machines and walks its endpoints |

The schema migrations are in `src/main/resources/db/migration`. Tests mirror the main
packages under `src/test/java`.

`scripts/smoke.sh [jar]` runs the smoke check from a built jar: it starts the real gateway
on a temporary directory against simulated machines, walks the endpoints a wall board and a
handheld use, stops it the way a service manager would, and exits 0 or 1. The same run is
part of `./mvnw verify`, so every build proves the service comes up.

## Conventions

Times are UTC everywhere except the machine clock, which has no zone and is set
in local time because that is what the plant reads off the carton. Lengths are
in dots, and millimetres appear only where a person types them. Voltages are
millivolts, pulses are nanoseconds, drops are picolitres, because that is what
the firmware works in and converting through floats loses the last DAC count.

Nothing here is corrected automatically. Drift is reported, alarms are reported,
a worn cartridge is reported. A machine on a running line is changed because
somebody decided to change it.
