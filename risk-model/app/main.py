import os

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from prometheus_client import Counter
from prometheus_fastapi_instrumentator import Instrumentator
from pydantic import BaseModel, Field

from .features import CONTRACT_FEATURES
from .scorer import Scorer

MODEL_VERSION = os.getenv("MODEL_VERSION", "v1")
MODEL_PATH = os.getenv("MODEL_PATH", "/app/model/model.pkl")

app = FastAPI(title="PhishLens risk-model")
scorer = Scorer(MODEL_VERSION, MODEL_PATH)
PREDICTIONS = Counter("phishlens_predictions_total", "Predictions served", ["model_version"])
Instrumentator().instrument(app).expose(app, endpoint="/metrics", include_in_schema=False)


class PredictRequest(BaseModel):
    url: str = Field(min_length=1)


@app.post("/predict")
def predict(req: PredictRequest):
    if not scorer.loaded:
        raise HTTPException(status_code=503, detail="model not loaded")
    score, features = scorer.score(req.url)
    PREDICTIONS.labels(MODEL_VERSION).inc()
    return {
        "score": score,
        "modelVersion": MODEL_VERSION,
        "features": {k: features[k] for k in CONTRACT_FEATURES},
    }


@app.get("/health")
def health():
    body = {"status": "ok" if scorer.loaded else "degraded", "modelLoaded": scorer.loaded, "modelVersion": MODEL_VERSION}
    return JSONResponse(body, status_code=200 if scorer.loaded else 503)
