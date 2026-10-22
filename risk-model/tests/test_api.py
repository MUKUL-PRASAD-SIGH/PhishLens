import pytest
from fastapi.testclient import TestClient

from app.scorer import Scorer

BAD = "http://192.168.10.5/secure-login/verify@account-update.xyz"


@pytest.fixture()
def client():
    from app import main
    return TestClient(main.app)


def test_predict_ok(client):
    r = client.post("/predict", json={"url": BAD})
    assert r.status_code == 200
    body = r.json()
    assert body["modelVersion"] == "v1" and body["score"] >= 0.70
    assert set(body["features"]) == {"length", "num_digits", "has_ip_host", "has_at_symbol",
                                     "num_subdomains", "suspicious_tld", "uses_https", "entropy"}


def test_predict_missing_url_is_422(client):
    assert client.post("/predict", json={}).status_code == 422


def test_health(client):
    r = client.get("/health")
    assert r.status_code == 200 and r.json()["modelLoaded"] is True


def test_metrics_exposed(client):
    client.post("/predict", json={"url": BAD})
    text = client.get("/metrics").text
    assert 'phishlens_predictions_total{model_version="v1"}' in text


def test_v2_without_model_file_is_not_loaded(tmp_path):
    assert Scorer("v2", str(tmp_path / "missing.pkl")).loaded is False
