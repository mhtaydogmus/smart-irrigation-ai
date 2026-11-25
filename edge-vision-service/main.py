import base64
import logging
import os
import threading
import time
from datetime import datetime, timezone

import cv2
import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

# --------------------------------------------------------------------------- #
# Configuration (override with environment variables)
# --------------------------------------------------------------------------- #
CAMERA_INDEX = int(os.getenv("CAMERA_INDEX", "0"))
CAPTURE_WIDTH = int(os.getenv("CAPTURE_WIDTH", "1280"))
CAPTURE_HEIGHT = int(os.getenv("CAPTURE_HEIGHT", "720"))
WARMUP_FRAMES = int(os.getenv("WARMUP_FRAMES", "5"))
JPEG_QUALITY = int(os.getenv("JPEG_QUALITY", "85"))

TARGET_WIDTH = 640
TARGET_HEIGHT = 360

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [edge-vision] %(message)s")
log = logging.getLogger("edge-vision")

app = FastAPI(title="Edge Vision Service", version="0.1.0")

_camera_lock = threading.Lock()


# --------------------------------------------------------------------------- #
# Response contract ({JSON + Base64}) — mirrored by CaptureResponse.java
# --------------------------------------------------------------------------- #
class CaptureResponse(BaseModel):
    stressDetected: bool
    stressType: str | None
    confidence: float
    imageBase64: str
    imageWidth: int
    imageHeight: int
    capturedAt: str


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
# Endpoints
# --------------------------------------------------------------------------- #
@app.post("/capture", response_model=CaptureResponse)
def capture() -> CaptureResponse:
    started = time.perf_counter()
    try:
        frame = capture_single_frame()
    except RuntimeError as exc:
        log.error("Capture failed: %s", exc)
        raise HTTPException(status_code=503, detail=str(exc)) from exc

    captured_at = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    resized = resize_preserving_aspect(frame)
    image_b64 = encode_jpeg_base64(resized)

    h, w = resized.shape[:2]
    log.info(
        "Snapshot captured: native=%dx%d output=%dx%d base64=%d chars in %.0f ms",
        frame.shape[1], frame.shape[0], w, h, len(image_b64), (time.perf_counter() - started) * 1000,
    )

    return CaptureResponse(
        stressDetected=False,
        stressType=None,
        confidence=0.0,
        imageBase64=image_b64,
        imageWidth=w,
        imageHeight=h,
        capturedAt=captured_at,
    )


@app.get("/health")
def health() -> dict:
    return {"status": "UP", "cameraIndex": CAMERA_INDEX}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000)
