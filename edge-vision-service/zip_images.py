"""Test photos from pepper-test.zip (release tag test-data-v1), read in memory. Nothing is extracted to disk."""
import logging
import random
import zipfile
from collections import Counter
from pathlib import Path

import cv2
import numpy as np

log = logging.getLogger("edge-vision")

CLASS_ORDER = ("healthy", "Fe", "Mg", "K")  # class ids used in the zip's label files
EXTENSIONS = {".jpg", ".jpeg", ".png"}


def _members(zip_path: Path) -> list[str] | None:
    """File entries of the zip, or None if the zip is missing or not a valid zip."""
    try:
        with zipfile.ZipFile(zip_path) as z:
            return [
                n for n in z.namelist()
                if not n.endswith("/") and "__MACOSX" not in n and not Path(n).name.startswith(".")
            ]
    except (OSError, zipfile.BadZipFile):
        return None


def image_names(zip_path: Path) -> list[str] | None:
    """Sorted image file names in the zip; None if the zip is missing or invalid."""
    members = _members(zip_path)
    if members is None:
        return None
    return sorted(Path(n).name for n in members if Path(n).suffix.lower() in EXTENSIONS)


def load(zip_path: Path, name: str | None = None) -> tuple[np.ndarray, str] | None:
    """(frame, file name) for one photo; a random photo if name is None; None if not available."""
    members = _members(zip_path)
    if not members:
        return None
    images = {Path(n).name: n for n in members if Path(n).suffix.lower() in EXTENSIONS}
    labels = {
        Path(n).stem: n for n in members
        if n.lower().endswith(".txt") and Path(n).name.lower() != "readme.txt"
    }
    if name is None:
        if not images:
            return None
        name = random.choice(sorted(images))
    if name not in images:
        return None

    with zipfile.ZipFile(zip_path) as z:
        data = z.read(images[name])
        label = labels.get(Path(name).stem)
        ids = [int(line.split()[0]) for line in z.read(label).decode("utf-8", "ignore").splitlines() if line.strip()] if label else []

    frame = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if frame is None:
        log.warning("Image '%s' in %s could not be decoded", name, zip_path.name)
        return None
    if ids:
        class_id = Counter(ids).most_common(1)[0][0]
        if class_id < len(CLASS_ORDER):
            log.info("zip photo %s: ground-truth class = %s", name, CLASS_ORDER[class_id])
    return frame, name
