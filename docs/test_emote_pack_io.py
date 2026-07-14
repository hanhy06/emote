import tempfile
import unittest
import zipfile
from pathlib import Path

from docs.emote_pack_io import find_pack_root, get_input_stem, prepare_work_dir, write_zip


class EmotePackIoTest(unittest.TestCase):
    def test_extracts_zip_and_finds_nested_pack_root(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir_name:
            temp_dir = Path(temp_dir_name)
            input_path = temp_dir / "input.zip"
            with zipfile.ZipFile(input_path, "w") as input_zip:
                input_zip.writestr("nested/pack.mcmeta", "{}")
                input_zip.writestr("nested/data/demo/file.txt", "value")

            work_dir = prepare_work_dir(input_path, temp_dir / "prepared")
            pack_root = find_pack_root(work_dir)

            self.assertEqual("{}", (pack_root / "pack.mcmeta").read_text(encoding="utf-8"))
            self.assertEqual("value", (pack_root / "data/demo/file.txt").read_text(encoding="utf-8"))

    def test_writes_pack_contents_without_parent_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir_name:
            temp_dir = Path(temp_dir_name)
            pack_root = temp_dir / "pack"
            (pack_root / "data/demo").mkdir(parents=True)
            (pack_root / "pack.mcmeta").write_text("{}", encoding="utf-8")
            (pack_root / "data/demo/file.txt").write_text("value", encoding="utf-8")
            output_path = temp_dir / "output.zip"
            output_path.write_text("previous output", encoding="utf-8")

            write_zip(pack_root, output_path)

            with zipfile.ZipFile(output_path) as output_zip:
                self.assertEqual(["data/demo/file.txt", "pack.mcmeta"], sorted(output_zip.namelist()))
            self.assertFalse((temp_dir / ".output.zip.tmp").exists())

    def test_removes_emote_prefix_from_input_name(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir_name:
            input_path = Path(temp_dir_name) / "emote.dance.zip"
            input_path.touch()

            self.assertEqual("dance", get_input_stem(input_path))


if __name__ == "__main__":
    unittest.main()
