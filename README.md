# nozzlewright

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
runs and configuration snapshots go into SQLite, with a different retention per
table and a janitor that enforces it.

**Says what is wrong before somebody phones.** A nozzle test decoded into which
dot rows will come out blank, and a message checked against them, so the answer
to whether the next carton will scan is arithmetic rather than opinion. Head
recovery that purges what a purge can fix and says when a cartridge is finished.
Machine clocks compared against the gateway, because every date on a carton
comes off one. Profile drift, so the setting somebody nudged at two in the
morning is found before the machine is swapped.

**Costs it.** Cost per thousand codes for a message, out of its dot count, the
drop volume and what a cartridge costs, with the purges counted in.

## Getting started

```
pip install -e ".[dev]"
nozzlewright --state-dir ./state db upgrade
nozzlewright --state-dir ./state --simulate registry add line4 10.4.0.21:9100 --line 4
nozzlewright --state-dir ./state --simulate printer status line4
```

`--simulate` swaps the real link for the machine model in `nozzlewright.simulator`,
which answers the whole catalogue. Everything below works against it, including
the gateway, which is how the demo on a training laptop runs.

Serving a site:

```
nozzlewright --state-dir ./state serve --host 0.0.0.0 --port 8420
```

The wall board is at `/board` and needs no key. Everything else is under an API
key when the site has any. `nozzlewright install` writes the systemd unit and its
environment file for a real box, and there is a `Dockerfile` for sites that run
containers.

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

## Layout

| package | what is in it |
| --- | --- |
| `protocol` | the catalogue, framing, the codec, dialects, and what each family can do |
| `transport` | TCP, RFCOMM and a loopback for the simulator |
| `session` | the command channel, login, the supervisor, and the shared link pool |
| `printer` | geometry, firing, the encoder, cartridges, status, nozzles, the clock |
| `labels` | parsing, fonts, barcodes, rastering, linting, diffing and verification |
| `fleet` | the registry, the poller, shifts, reports, OEE, costing, notices, drift |
| `storage` | the models, the repositories, migrations, the recorder and the janitor |
| `api` | the HTTP surface, the wall board and the key ring |
| `cli` | everything a person types |
| `simulator` | a machine that answers the catalogue, for tests and for training |

Tests are in `tests/`, one file per module, and there is no test that reaches a
real printer. `python scripts/checks.py` runs the formatter, the linter, the
type checker and the suite; `python scripts/smoke.py` brings a whole gateway up
against simulated machines and walks the endpoints.

## Conventions

Times are UTC everywhere except the machine clock, which has no zone and is set
in local time because that is what the plant reads off the carton. Lengths are
in dots, and millimetres appear only where a person types them. Voltages are
millivolts, pulses are nanoseconds, drops are picolitres, because that is what
the firmware works in and converting through floats loses the last DAC count.

Nothing here is corrected automatically. Drift is reported, alarms are reported,
a worn cartridge is reported. A machine on a running line is changed because
somebody decided to change it.
