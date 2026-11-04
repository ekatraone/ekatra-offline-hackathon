# Ekatra Offline

Offline AI learning system that serves learners over local Wi-Fi with minimal infrastructure.

## Current Role

This repository is the offline or edge companion to the main platform. It is not the primary product codebase for shared backend work.

## What This Project Contains
- Python backend and local web interface.
- Startup and management scripts for local deployment.
- Optional Azure and Raspberry Pi helper workflows.
- Frontend assets for learner and admin interfaces.

## Run Commands

```bash
# First-time setup
chmod +x setup.sh
./setup.sh

# Start system
./start.sh

# Alternative start
./run.sh

# System status
./status.sh

# Restart
./restart.sh
```

## Preferred Entrypoint

- Preferred server entrypoint: [server.py](server.py)
- Specialized deployment entrypoint: [lightweight_server.py](lightweight_server.py) for Raspberry Pi and tunnel workflows
- Retired 2026-11-04: the legacy `main.py`, `main_v2.py` and `main_react.py` wrappers were removed; run `python3 server.py` instead

## Developer Checklist
- Confirm Python and virtual environment are available.
- Install dependencies from requirements files.
- Ensure .env exists and required variables are configured.
- Start server and verify learner URL and admin URL load.
- Validate chat flow and model response behavior.
- Review logs before release.

## Important File Links
- Preferred server entry: [server.py](server.py)
- Specialized lightweight path: [lightweight_server.py](lightweight_server.py)
- Startup scripts: [start.sh](start.sh), [run.sh](run.sh), [restart.sh](restart.sh)
- Health/status script: [status.sh](status.sh)
- Setup script: [setup.sh](setup.sh)
- Dependencies: [requirements.txt](requirements.txt), [requirements-azure.txt](requirements-azure.txt)
- Frontend folder: [frontend](frontend)
- Templates: [templates](templates)
- Static assets: [static](static)
- Config folder: [config](config)
- Docs folder: [ekatra_docs](ekatra_docs)

## Notes
- Intended to run fully offline after initial setup.
- Keep model settings aligned with available RAM and CPU.
- Treat alternate top-level server files as migration targets, not equal first-choice runtimes.
