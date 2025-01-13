# nozzlewright

Handheld fleet controller for Halvex thermal inkjet coding printers.

This is the Python service that sits behind the handheld terminal and the line-side
gateway. It speaks the Halvex line protocol over Bluetooth RFCOMM or TCP, keeps a
supervised session per printer, renders label templates down to nozzle columns,
and tracks ink, firing profiles and encoder calibration across a site.

Supported machine families: TIJ 4-pen, TIJ 8-pen, HVXDOD and HVXLCP.

## Layout

    src/nozzlewright/protocol   wire format, command catalogue, status decoding
    src/nozzlewright/transport  RFCOMM and TCP links
    src/nozzlewright/session    command queue, keepalive, authentication
    src/nozzlewright/printer    geometry, firing, encoder, cartridge, config
    src/nozzlewright/labels     template parsing, dynamic fields, rastering
    src/nozzlewright/fleet      registry, polling, alarms, config rollout
    tests                     the whole suite

## Running the tests

    python -m venv .venv
    .venv/bin/pip install -e ".[dev]"
    .venv/bin/pytest
