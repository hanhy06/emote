import os
from pathlib import Path
import shutil
import subprocess


PROJECT_DIR = Path(__file__).resolve().parents[2]
WEB_DIR = PROJECT_DIR / "web"
ASSETS_DIR = Path(__file__).resolve().parent / "assets"


def on_post_build(config, **kwargs):
    site_dir = Path(config.site_dir).resolve()
    shutil.copytree(ASSETS_DIR, site_dir / "assets", dirs_exist_ok=True)
    if os.environ.get("EMOTE_SKIP_CONVERTER") == "1":
        return

    npm = shutil.which("npm")
    if npm is None:
        raise RuntimeError("npm was not found. Install Node.js before building the site.")

    subprocess.run(
        [
            npm,
            "run",
            "build",
            "--",
            "--outDir",
            str(site_dir / "converter"),
            "--emptyOutDir",
            "--base",
            "./",
        ],
        cwd=WEB_DIR,
        check=True,
    )
