from pathlib import Path
import shutil
import subprocess


PROJECT_DIR = Path(__file__).resolve().parents[2]
WEB_DIR = PROJECT_DIR / "web"
ASSETS_DIR = Path(__file__).resolve().parent / "assets"


def on_post_build(config, **kwargs):
    npm = shutil.which("npm")
    if npm is None:
        raise RuntimeError("npm was not found. Install Node.js before building the site.")

    converter_dir = Path(config.site_dir).resolve() / "converter"

    subprocess.run(
        [
            npm,
            "run",
            "build",
            "--",
            "--outDir",
            str(converter_dir),
            "--emptyOutDir",
            "--base",
            "./",
        ],
        cwd=WEB_DIR,
        check=True,
    )

    shutil.copytree(ASSETS_DIR, Path(config.site_dir).resolve() / "assets", dirs_exist_ok=True)
