"""Scoring: v1 weighted heuristic and v2 trained model."""
import os

import joblib

from .features import extract_features, to_vector

# v1 weights. scan-api's FallbackScorer copies these (docs/TASKS.md, Day 5 handoff).
V1_WEIGHTS = {
    "bias": 0.05,
    "has_ip_host": 0.30,
    "has_at_symbol": 0.20,
    "suspicious_tld": 0.15,
    "not_https": 0.10,
    "subdomain": 0.05,   # per subdomain, capped at 3
    "length": 0.10,      # length / 100, capped at 1
    "digits": 0.10,      # digits / 10, capped at 1
}


def score_v1(f: dict) -> float:
    w = V1_WEIGHTS
    s = (
        w["bias"]
        + w["has_ip_host"] * f["has_ip_host"]
        + w["has_at_symbol"] * f["has_at_symbol"]
        + w["suspicious_tld"] * f["suspicious_tld"]
        + w["not_https"] * (not f["uses_https"])
        + w["subdomain"] * min(f["num_subdomains"], 3)
        + w["length"] * min(f["length"] / 100, 1)
        + w["digits"] * min(f["num_digits"] / 10, 1)
    )
    return max(0.0, min(1.0, s))


class Scorer:
    def __init__(self, version: str, model_path: str):
        self.version = version
        self.model = None
        if version == "v2" and os.path.exists(model_path):
            self.model = joblib.load(model_path)

    @property
    def loaded(self) -> bool:
        return self.version == "v1" or self.model is not None

    def score(self, url: str) -> tuple[float, dict]:
        f = extract_features(url)
        if self.version == "v2":
            prob = float(self.model.predict_proba([to_vector(f)])[0][1])
        else:
            prob = score_v1(f)
        return round(prob, 2), f
