from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path

if __package__:
    from .emote_pack_io import get_input_stem
else:
    from emote_pack_io import get_input_stem


EMOTE_META_FILE_NAME = "emote.json"


@dataclass(frozen=True)
class EmoteMetadata:
    name: str
    description: str
    command_name: str
    entrypoint: str
    hide_player: bool


@dataclass(frozen=True)
class EmoteTarget:
    namespace: str
    entrypoint: str


def create_emote_metadata(
    pack_root: Path,
    input_path: Path,
    targets: list[EmoteTarget],
    args: argparse.Namespace,
) -> dict[str, EmoteMetadata]:
    metadata: dict[str, EmoteMetadata] = {}
    multiple_emotes = len(targets) > 1
    for index, target in enumerate(targets, start=1):
        existing_meta = load_existing_meta(pack_root, target.namespace)
        if multiple_emotes:
            name = f"{args.name} {index}" if args.name else target.namespace
            command_name = f"{sanitize_command_name(args.command_name)}_{index}" if args.command_name else target.namespace
        else:
            name = args.name or str(existing_meta.get("name") or prettify_name(get_input_stem(input_path)))
            command_name = sanitize_command_name(args.command_name or str(
                existing_meta.get("command_name") or target.namespace
            ))

        description = args.description or str(existing_meta.get("description") or f"{name} emote.")
        metadata[target.namespace] = EmoteMetadata(
            name=name,
            description=description,
            command_name=command_name,
            entrypoint=target.entrypoint,
            hide_player=args.hide_player,
        )
    return metadata


def load_existing_meta(pack_root: Path, namespace: str) -> dict[str, object]:
    meta_path = pack_root / "data" / namespace / EMOTE_META_FILE_NAME
    if not meta_path.exists():
        return {}
    try:
        loaded_meta = json.loads(meta_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}
    return loaded_meta if isinstance(loaded_meta, dict) else {}


def write_emote_metadata(pack_root: Path, metadata: dict[str, EmoteMetadata]) -> None:
    for namespace, emote_meta in metadata.items():
        meta = {
            "schema_version": 3,
            "name": emote_meta.name,
            "description": emote_meta.description,
            "command_name": emote_meta.command_name,
            "entrypoint": emote_meta.entrypoint,
            "hide_player": emote_meta.hide_player,
        }
        meta_path = pack_root / "data" / namespace / EMOTE_META_FILE_NAME
        meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def prettify_name(value: str) -> str:
    prettified_value = value.replace("_", " ").replace("-", " ").strip()
    return prettified_value or value


def sanitize_command_name(value: str) -> str:
    command_name = re.sub(r"[^a-z0-9_-]+", "_", value.lower()).strip("_")
    return command_name or "emote"
