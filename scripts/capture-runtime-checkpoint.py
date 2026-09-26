#!/usr/bin/env python3
"""Capture a small AndroidKlipper runtime checkpoint from Moonraker.

This is intentionally stdlib-only so it can run from a random laptop/Pi without
setting up another Python environment. It saves useful evidence, not giant logs.
"""

import argparse
import json
from datetime import datetime
from pathlib import Path
from urllib.parse import quote
from urllib.request import Request, urlopen


def fetch(url: str, *, tail_bytes: int | None = None) -> bytes:
    headers = {"Range": f"bytes=-{tail_bytes}"} if tail_bytes else {}
    with urlopen(Request(url, headers=headers), timeout=8) as response:
        return response.read()


def fetch_json(url: str) -> dict:
    return json.loads(fetch(url).decode("utf-8"))


def save_json(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="192.168.1.54", help="AndroidKlipper device IP")
    parser.add_argument("--port", type=int, default=7125, help="Moonraker port")
    parser.add_argument("--out-dir", default="test-artifacts/runtime", help="Checkpoint output directory")
    args = parser.parse_args()

    stamp = datetime.now().astimezone().strftime("%Y%m%d-%H%M%S-%z")
    out = Path(args.out_dir) / stamp
    out.mkdir(parents=True, exist_ok=True)
    base = f"http://{args.host}:{args.port}"

    # Keep this query focused on the values that actually helped diagnose v249/v252.
    objects = "&".join(
        quote(name, safe="")
        for name in (
            "webhooks", "print_stats", "virtual_sdcard", "mcu", "system_stats", "mcu nhk", "mcu eddy"
        )
    )
    endpoints = {
        "objects": f"{base}/printer/objects/query?{objects}",
        "server-info": f"{base}/server/info",
        "history": f"{base}/server/history/list?limit=10",
        "logs-list": f"{base}/server/files/list?root=logs",
    }

    captured: dict[str, dict] = {}
    for name, url in endpoints.items():
        payload = fetch_json(url)
        captured[name] = payload
        save_json(out / f"{name}.json", payload)

    # Host log is small and purpose-built for lifecycle evidence, so keep all of it.
    (out / "androidklipper-host.log").write_bytes(fetch(f"{base}/server/files/logs/androidklipper-host.log"))

    # Moonraker's log can be huge. The last 128 KiB is enough for a checkpoint.
    (out / "moonraker-tail.log").write_bytes(
        fetch(f"{base}/server/files/logs/moonraker.log", tail_bytes=128 * 1024)
    )

    status = captured["objects"].get("result", {}).get("status", {})
    print_stats = status.get("print_stats", {})
    main_mcu = status.get("mcu", {}).get("last_stats", {})
    nhk_mcu = status.get("mcu nhk", {}).get("last_stats", {})
    eddy_mcu = status.get("mcu eddy", {}).get("last_stats", {})

    lines = [
        "# AndroidKlipper runtime checkpoint",
        "",
        f"Captured: {datetime.now().astimezone().isoformat()}",
        f"Host: {args.host}:{args.port}",
        f"Print state: {print_stats.get('state', 'unknown')}",
        f"File: {print_stats.get('filename', '')}",
        f"Print duration: {print_stats.get('print_duration', 0)}",
        f"Main retransmit/invalid: {main_mcu.get('bytes_retransmit', 0)}/{main_mcu.get('bytes_invalid', 0)}",
        f"NHK retransmit/invalid: {nhk_mcu.get('bytes_retransmit', 0)}/{nhk_mcu.get('bytes_invalid', 0)}",
        f"Eddy retransmit/invalid: {eddy_mcu.get('bytes_retransmit', 0)}/{eddy_mcu.get('bytes_invalid', 0)}",
        "",
        "Raw JSON and log excerpts are beside this summary.",
    ]
    (out / "SUMMARY.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(out.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
