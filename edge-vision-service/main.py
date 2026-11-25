import base64
import logging
import os
import re
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

import cv2
import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

import zip_images

# --------------------------------------------------------------------------- #
# Configuration (override with environment variables)
# --------------------------------------------------------------------------- #
SERVICE_DIR = Path(__file__).resolve().parent

CAPTURE_MODE = os.getenv("CAPTURE_MODE", "FOLDER").strip().upper()
TEST_IMAGES_DIR = Path(os.getenv("TEST_IMAGES_DIR", SERVICE_DIR.parent / "test-images")).resolve()
MODEL_PATH = Path(os.getenv("MODEL_PATH", SERVICE_DIR / "models" / "best.pt")).resolve()
TEST_IMAGES_ZIP = Path(os.getenv("TEST_IMAGES_ZIP", SERVICE_DIR.parent / "pepper-test.zip")).resolve()

CAMERA_INDEX = int(os.getenv("CAMERA_INDEX", "0"))
CAPTURE_WIDTH = int(os.getenv("CAPTURE_WIDTH", "1280"))
CAPTURE_HEIGHT = int(os.getenv("CAPTURE_HEIGHT", "720"))
WARMUP_FRAMES = int(os.getenv("WARMUP_FRAMES", "5"))
JPEG_QUALITY = int(os.getenv("JPEG_QUALITY", "85"))

TARGET_WIDTH = 640
TARGET_HEIGHT = 360
SUPPORTED_EXTENSIONS = {".jpg", ".jpeg", ".png"}

if CAPTURE_MODE not in {"FOLDER", "CAMERA"}:
    raise RuntimeError(f"Invalid CAPTURE_MODE '{CAPTURE_MODE}', expected FOLDER or CAMERA")

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [edge-vision] %(message)s")
log = logging.getLogger("edge-vision")

app = FastAPI(title="Edge Vision Service", version="0.2.0")

_camera_lock = threading.Lock()
_model_lock = threading.Lock()
_model = None


# --------------------------------------------------------------------------- #
# Request / response contract ({JSON + Base64}) — mirrored by CaptureResponse.java
# --------------------------------------------------------------------------- #
class CaptureRequest(BaseModel):
    imagePath: str | None = None


class CaptureResponse(BaseModel):
    fileName: str
    stressDetected: bool
    stressType: str
    confidence: float
    inferenceSource: str
    imageBase64: str
    imageWidth: int
    imageHeight: int
    capturedAt: str


# --------------------------------------------------------------------------- #
# Folder: image file input
# --------------------------------------------------------------------------- #
def is_supported_image(path: Path) -> bool:
    return path.is_file() and not path.name.startswith(".") and path.suffix.lower() in SUPPORTED_EXTENSIONS


def find_latest_image() -> Path:
    candidates = [p for p in TEST_IMAGES_DIR.iterdir() if is_supported_image(p)] if TEST_IMAGES_DIR.is_dir() else []
    if not candidates:
        raise HTTPException(status_code=404, detail=f"No .jpg/.jpeg/.png images found in {TEST_IMAGES_DIR}")
    return max(candidates, key=lambda p: p.stat().st_mtime)


def load_image_file(image_path: str | None) -> tuple[np.ndarray, str]:
    if image_path:
        path = Path(image_path).expanduser()
        if not path.is_absolute():
            path = TEST_IMAGES_DIR / path
        if not path.is_file():
            zipped = zip_images.load(TEST_IMAGES_ZIP, path.name)
            if zipped is not None:
                return zipped
    else:
        try:
            path = find_latest_image()
        except HTTPException:
            zipped = zip_images.load(TEST_IMAGES_ZIP)
            if zipped is not None:
                return zipped
            raise HTTPException(status_code=404, detail=f"No images in {TEST_IMAGES_DIR} and no usable ZIP at {TEST_IMAGES_ZIP}") from None

    if not path.is_file():
        raise HTTPException(status_code=404, detail=f"Image not found: {path}")
    if path.suffix.lower() not in SUPPORTED_EXTENSIONS:
        raise HTTPException(status_code=400, detail=f"Unsupported image type '{path.suffix}', expected .jpg, .jpeg or .png")

    frame = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if frame is None:
        raise HTTPException(status_code=400, detail=f"Image could not be decoded: {path}")
    return frame, path.name


# --------------------------------------------------------------------------- #
# Camera: single snapshot
# --------------------------------------------------------------------------- #
def capture_single_frame() -> np.ndarray:
    with _camera_lock:
        cap = cv2.VideoCapture(CAMERA_INDEX)
        try:
            if not cap.isOpened():
                raise RuntimeError(f"Could not open USB webcam at index {CAMERA_INDEX}")

            cap.set(cv2.CAP_PROP_FRAME_WIDTH, CAPTURE_WIDTH)
            cap.set(cv2.CAP_PROP_FRAME_HEIGHT, CAPTURE_HEIGHT)

            for _ in range(WARMUP_FRAMES):
                cap.grab()

            ok, frame = cap.read()
            if not ok or frame is None:
                raise RuntimeError("Webcam opened but returned no frame")
            return frame
        finally:
            cap.release()


# --------------------------------------------------------------------------- #
# Preprocessing: 640x360 resize + JPEG Base64
# --------------------------------------------------------------------------- #
def resize_preserving_aspect(frame: np.ndarray) -> np.ndarray:
    h, w = frame.shape[:2]
    scale = min(TARGET_WIDTH / w, TARGET_HEIGHT / h)
    new_w, new_h = round(w * scale), round(h * scale)

    if (new_w, new_h) != (TARGET_WIDTH, TARGET_HEIGHT):
        log.warning(
            "Native resolution %dx%d is not 16:9; output is %dx%d instead of %dx%d "
            "(aspect ratio preserved, no crop/pad)", w, h, new_w, new_h, TARGET_WIDTH, TARGET_HEIGHT,
        )

    interpolation = cv2.INTER_AREA if scale < 1 else cv2.INTER_LINEAR
    return cv2.resize(frame, (new_w, new_h), interpolation=interpolation)


def encode_jpeg_base64(frame: np.ndarray) -> str:
    ok, buffer = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
    if not ok:
        raise RuntimeError("JPEG encoding failed")
    return base64.b64encode(buffer.tobytes()).decode("ascii")


# --------------------------------------------------------------------------- #
# AI inference: YOLO model (models/best.pt) with filename-based mock fallback
# --------------------------------------------------------------------------- #
MOCK_RULES = (
    (("healthy",), (), "healthy", 0.96),
    (("magnesium",), ("mg",), "mg", 0.85),
    (("iron",), ("fe",), "fe", 0.88),
    (("potassium",), ("k",), "k", 0.87),
)
MOCK_DEFAULT = ("healthy", 0.50)

STRESS_LABELS = {"fe": "iron_deficiency", "mg": "magnesium_deficiency", "k": "potassium_deficiency"}


def get_model():
    global _model
    with _model_lock:
        if _model is None and MODEL_PATH.is_file():
            try:
                from ultralytics import YOLO
            except ImportError as exc:
                raise HTTPException(
                    status_code=500, detail="models/best.pt exists but 'ultralytics' is not installed",
                ) from exc
            _model = YOLO(str(MODEL_PATH))
            log.info("Loaded YOLO model from %s", MODEL_PATH)
        return _model


def mock_inference(filename: str) -> dict:
    name = Path(filename).stem.lower()
    tokens = set(re.split(r"[^a-z0-9]+", name))
    class_name, confidence = next(
        (
            (label, conf) for words, codes, label, conf in MOCK_RULES
            if any(w in name for w in words) or any(c in tokens for c in codes)
        ),
        MOCK_DEFAULT,
    )
    stress_type = STRESS_LABELS.get(class_name, class_name)
    return {
        "stressDetected": stress_type != "healthy",
        "stressType": stress_type,
        "confidence": confidence,
        "inferenceSource": "mock",
    }


def run_yolo_inference(image: np.ndarray, filename: str) -> dict:
    model = get_model()
    if model is None:
        log.warning("No model at %s, using mock inference for '%s'", MODEL_PATH, filename)
        return mock_inference(filename)

    result = model.predict(image, verbose=False)[0]
    if getattr(result, "probs", None) is not None:
        class_id = int(result.probs.top1)
        confidence = float(result.probs.top1conf)
    elif result.boxes is not None and len(result.boxes) > 0:
        best = int(result.boxes.conf.argmax())
        class_id = int(result.boxes.cls[best])
        confidence = float(result.boxes.conf[best])
    else:
        return {"stressDetected": False, "stressType": "healthy", "confidence": 0.0, "inferenceSource": "model"}

    class_name = str(result.names[class_id]).strip().lower()
    stress_type = STRESS_LABELS.get(class_name, class_name)
    return {
        "stressDetected": stress_type != "healthy",
        "stressType": stress_type,
        "confidence": round(confidence, 4),
        "inferenceSource": "model",
    }


# --------------------------------------------------------------------------- #
# Endpoints
# --------------------------------------------------------------------------- #
@app.post("/capture", response_model=CaptureResponse)
def capture(request: CaptureRequest | None = None) -> CaptureResponse:
    started = time.perf_counter()

    if CAPTURE_MODE == "CAMERA":
        try:
            frame = capture_single_frame()
        except RuntimeError as exc:
            log.error("Capture failed: %s", exc)
            raise HTTPException(status_code=503, detail=str(exc)) from exc
        file_name = f"camera-{CAMERA_INDEX}-snapshot.jpg"
    else:
        frame, file_name = load_image_file(request.imagePath if request else None)

    captured_at = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    resized = resize_preserving_aspect(frame)
    detection = run_yolo_inference(resized, file_name)
    image_b64 = encode_jpeg_base64(resized)

    h, w = resized.shape[:2]
    log.info(
        "[%s] %s: native=%dx%d output=%dx%d -> %s (%.2f, %s) in %.0f ms",
        CAPTURE_MODE, file_name, frame.shape[1], frame.shape[0], w, h,
        detection["stressType"], detection["confidence"], detection["inferenceSource"],
        (time.perf_counter() - started) * 1000,
    )

    return CaptureResponse(
        fileName=file_name,
        imageBase64=image_b64,
        imageWidth=w,
        imageHeight=h,
        capturedAt=captured_at,
        **detection,
    )


@app.get("/health")
def health() -> dict:
    return {
        "status": "UP",
        "captureMode": CAPTURE_MODE,
        "testImagesDir": str(TEST_IMAGES_DIR),
        "modelPresent": MODEL_PATH.is_file(),
        "cameraIndex": CAMERA_INDEX,
        "testZipPresent": zip_images.image_names(TEST_IMAGES_ZIP) is not None,
    }


if __name__ == "__main__":
    import uvicorn

    log.info("Starting in %s mode (test images: %s)", CAPTURE_MODE, TEST_IMAGES_DIR)
    uvicorn.run("main:app", host="0.0.0.0", port=8000)
