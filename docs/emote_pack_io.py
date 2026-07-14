from __future__ import annotations

import shutil
import zipfile
from pathlib import Path


def validate_input_path(input_path: Path) -> None:
    if not input_path.exists():
        raise SystemExit("The input path does not exist.")
    if input_path.is_file() and input_path.suffix.lower() != ".zip":
        raise SystemExit("The input file must be a .zip.")
    if not input_path.is_file() and not input_path.is_dir():
        raise SystemExit("The input path must be a .zip file or folder.")


def create_output_path(input_path: Path, output_dir: Path | None) -> Path:
    return create_output_path_for_name(get_input_stem(input_path), input_path, output_dir)


def create_output_path_for_name(name: str, input_path: Path, output_dir: Path | None) -> Path:
    parent = output_dir.resolve() if output_dir is not None else input_path.parent
    parent.mkdir(parents=True, exist_ok=True)
    return parent / f"emote.{name}.zip"


def prepare_work_dir(input_path: Path, temp_dir: Path) -> Path:
    work_dir = temp_dir / "work"
    work_dir.mkdir(parents=True, exist_ok=True)
    if input_path.is_dir():
        copy_dir = work_dir / input_path.name
        shutil.copytree(input_path, copy_dir)
        return copy_dir

    extract_dir = work_dir / "extract"
    extract_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(input_path) as input_zip_file:
        input_zip_file.extractall(extract_dir)
    return extract_dir


def find_pack_root(extract_dir: Path) -> Path:
    pack_meta_paths = sorted(
        extract_dir.rglob("pack.mcmeta"),
        key=lambda path: (len(path.parts), str(path).lower()),
    )
    if not pack_meta_paths:
        raise SystemExit("pack.mcmeta was not found inside the input path.")
    return pack_meta_paths[0].parent


def get_input_stem(input_path: Path) -> str:
    stem = input_path.stem if input_path.is_file() else input_path.name
    return stem.removeprefix("emote.")


def write_zip(pack_root: Path, output_path: Path) -> None:
    if output_path.exists():
        output_path.unlink()

    with zipfile.ZipFile(output_path, "w", compression=zipfile.ZIP_DEFLATED) as output_zip_file:
        for path in sorted(pack_root.rglob("*")):
            if not path.is_dir():
                output_zip_file.write(path, arcname=path.relative_to(pack_root))
