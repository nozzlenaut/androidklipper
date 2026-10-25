"""AndroidKlipper extension staging and Python import activation.

This is a host-side foundation, not a complete Happy Hare integration.
Only registered extensions with known layouts are accepted. Linux installers
are never executed.
"""
import importlib
import json
import os
from pathlib import Path
import shutil
import stat
import tempfile
import zipfile


class ExtensionError(ValueError):
    pass


CATALOG = {
    "happy_hare": {
        "source": "moggieuk/Happy-Hare",
        "paths": {
            "extras/": "klipper/extras/",
            "components/": "moonraker/components/",
            "config/": "templates/",
        },
        "required": (
            "klipper/extras/mmu/__init__.py",
            "moonraker/components/mmu_server.py",
        ),
    },
}
MAX_ARCHIVE_BYTES = 30 * 1024 * 1024
MAX_UNCOMPRESSED_BYTES = 60 * 1024 * 1024
MAX_ENTRIES = 3000


def _safe_parts(value):
    if not value or "\\" in value or "\x00" in value or value.startswith("/"):
        raise ExtensionError("unsafe archive path")
    parts = value.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise ExtensionError("unsafe archive path")
    return parts


def install_archive(extensions_dir, extension_id, archive_path, version="unverified"):
    """Stage a known upstream source ZIP without running executable installers.

    Caller must stop Klippy and Moonraker and verify archive provenance first.
    Installed modules only become active on host restart.
    """
    if extension_id not in CATALOG:
        raise ExtensionError("unsupported extension")
    if os.path.getsize(archive_path) > MAX_ARCHIVE_BYTES:
        raise ExtensionError("archive too large")
    spec = CATALOG[extension_id]
    base = Path(extensions_dir)
    base.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".staging-", dir=base))
    dest = base / extension_id
    backup = base / (".previous-" + extension_id)
    moved = False
    try:
        with zipfile.ZipFile(archive_path) as archive:
            records = archive.infolist()
            if len(records) > MAX_ENTRIES:
                raise ExtensionError("too many files")
            selected = []
            total = 0
            for entry in records:
                if entry.is_dir():
                    continue
                parts = _safe_parts(entry.filename)
                if len(parts) < 3:
                    continue
                mode = entry.external_attr >> 16
                if stat.S_IFMT(mode) == stat.S_IFLNK:
                    raise ExtensionError("symlink entry rejected")
                inner = "/".join(parts[1:])
                mapping = next((
                    (src, tgt) for src, tgt in spec["paths"].items()
                    if inner.startswith(src)
                ), None)
                if mapping is None:
                    continue
                src, tgt = mapping
                suffix = inner[len(src):]
                if not suffix or not suffix.endswith((".py", ".cfg")):
                    continue
                _safe_parts(suffix)
                target = stage / tgt / suffix
                if not target.resolve().is_relative_to(stage.resolve()):
                    raise ExtensionError("entry escapes extension directory")
                total += entry.file_size
                if total > MAX_UNCOMPRESSED_BYTES:
                    raise ExtensionError("extracted files exceed size limit")
                selected.append((entry, target))
            names = [target.relative_to(stage).as_posix() for _, target in selected]
            if len(names) != len(set(names)):
                raise ExtensionError("duplicate file in archive")
            missing = set(spec["required"]) - set(names)
            if missing:
                raise ExtensionError("incomplete extension: " + ", ".join(sorted(missing)))
            for entry, target in selected:
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(entry) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
            (stage / "extension.json").write_text(json.dumps({
                "id": extension_id,
                "source": spec["source"],
                "version": version,
                "enabled": True,
            }, sort_keys=True), encoding="utf-8")

        if backup.exists():
            shutil.rmtree(backup)
        if dest.exists():
            os.replace(dest, backup)
            moved = True
        try:
            os.replace(stage, dest)
        except Exception:
            if moved:
                os.replace(backup, dest)
            raise
        return {
            "extension": extension_id,
            "files": len(selected),
            "restart_required": True,
        }
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def activate(extensions_dir, package):
    """Expose app-private modules to an existing embedded Python package.

    package='extras' for Klippy or 'moonraker.components' for Moonraker.
    Must run before plugin discovery, not while printing.
    """
    subdir = {
        "extras": "klipper/extras",
        "moonraker.components": "moonraker/components",
    }.get(package)
    if subdir is None:
        raise ExtensionError("unknown Python package")
    root = Path(extensions_dir)
    active = []
    for name in CATALOG:
        location = root / name
        manifest = location / "extension.json"
        if not manifest.is_file():
            continue
        info = json.loads(manifest.read_text(encoding="utf-8"))
        if info.get("id") != name or info.get("enabled") is not True:
            continue
        target = location / subdir
        if not target.is_dir():
            continue
        pkg = importlib.import_module(package)
        path = str(target.resolve())
        if path not in pkg.__path__:
            pkg.__path__.append(path)
        active.append(name)
    importlib.invalidate_caches()
    return active
