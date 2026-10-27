#!/usr/bin/env python3
"""NextCraft Forge reproducible GNU tar builder for runtime overlays."""

import argparse
import os
import tarfile
from pathlib import Path

FIXED_EPOCH = 1_757_116_800
IGNORED_NAMES = {".DS_Store"}


def _sanitize_header(member: tarfile.TarInfo) -> tarfile.TarInfo:
    member.uid = 0
    member.gid = 0
    member.uname = "root"
    member.gname = "root"
    member.mtime = FIXED_EPOCH
    member.pax_headers = {}
    if member.isdir():
        member.mode = 0o755
    elif member.isfile():
        is_exec = bool(member.mode & 0o111)
        member.mode = 0o755 if is_exec else 0o644
    return member


def _collect_sorted_nodes(root_dir: Path) -> list[Path]:
    discovered: list[Path] = []
    for base, subdirs, filenames in os.walk(root_dir, followlinks=False):
        subdirs[:] = sorted(d for d in subdirs if not d.startswith("._"))
        valid_files = sorted(f for f in filenames if not f.startswith("._") and f not in IGNORED_NAMES)
        base_path = Path(base)
        if base_path != root_dir:
            discovered.append(base_path)
        for dirname in subdirs:
            candidate_dir = base_path / dirname
            if candidate_dir.is_symlink():
                discovered.append(candidate_dir)
        for fname in valid_files:
            discovered.append(base_path / fname)
    discovered.sort(key=lambda item: item.relative_to(root_dir).as_posix())
    return discovered


def build_reproducible_tar(source_root: Path, output_tar: Path) -> None:
    src = source_root.resolve()
    dst = output_tar.resolve()
    ordered_nodes = _collect_sorted_nodes(src)
    with tarfile.open(dst, mode="w", format=tarfile.GNU_FORMAT, dereference=False) as tf:
        for item_path in ordered_nodes:
            rel_arcname = item_path.relative_to(src).as_posix()
            tf.add(item_path, arcname=rel_arcname, recursive=False, filter=_sanitize_header)


def _cli() -> None:
    parser = argparse.ArgumentParser(description="Build a reproducible root-owned tar archive.")
    parser.add_argument("source_dir", type=Path)
    parser.add_argument("output_tar", type=Path)
    args = parser.parse_args()
    build_reproducible_tar(args.source_dir, args.output_tar)


if __name__ == "__main__":
    _cli()
