import os

from fastapi.testclient import TestClient

BAD = "http://192.168.10.5/secure-login/verify@account-update.xyz"
MODEL = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "model", "model.pkl"))


def _client(monkeypatch, version, path=MODEL):
    import app.main as main
    from app.scorer import Scorer
    monkeypatch.setattr(main, "MODEL_VERSION", version)
    monkeypatch.setattr(main, "scorer", Scorer(version, path))
    return TestClient(main.app)


def test_empty_url_is_422(monkeypatch):
    assert _client(monkeypatch, "v1").post("/predict", json={"url": ""}).status_code == 422


def test_wrong_type_is_422(monkeypatch):
    assert _client(monkeypatch, "v1").post("/predict", json={"url": 123}).status_code == 422


def test_get_predict_not_allowed(monkeypatch):
    assert _client(monkeypatch, "v1").get("/predict").status_code == 405


def test_unknown_path_404(monkeypatch):
    assert _client(monkeypatch, "v1").get("/nope").status_code == 404


def test_v1_health_reports_version(monkeypatch):
    body = _client(monkeypatch, "v1").get("/health").json()
    assert body == {"status": "ok", "modelLoaded": True, "modelVersion": "v1"}


def test_v2_missing_model_gives_503_on_health_and_predict(monkeypatch, tmp_path):
    c = _client(monkeypatch, "v2", str(tmp_path / "missing.pkl"))
    r = c.get("/health")
    assert r.status_code == 503 and r.json()["modelLoaded"] is False
    assert c.post("/predict", json={"url": BAD}).status_code == 503


def test_v2_with_real_model_serves_predictions(monkeypatch):
    c = _client(monkeypatch, "v2", MODEL)
    assert c.get("/health").json()["modelVersion"] == "v2"
    r = c.post("/predict", json={"url": BAD})
    assert r.status_code == 200 and r.json()["modelVersion"] == "v2" and r.json()["score"] > 0.7


def test_response_matches_spec_shape(monkeypatch):
    body = _client(monkeypatch, "v1").post("/predict", json={"url": BAD}).json()
    assert set(body) == {"score", "modelVersion", "features"}
    assert isinstance(body["score"], float) and 0 <= body["score"] <= 1
    assert body["features"]["has_ip_host"] is True and body["features"]["has_at_symbol"] is True


def test_url_without_scheme_is_scored(monkeypatch):
    assert _client(monkeypatch, "v1").post("/predict", json={"url": "example.com/login"}).status_code == 200


def test_prediction_counter_increments_per_request(monkeypatch):
    c = _client(monkeypatch, "v1")

    def count():
        for line in c.get("/metrics").text.splitlines():
            if line.startswith('phishlens_predictions_total{model_version="v1"}'):
                return float(line.split()[-1])
        return 0.0

    before = count()
    for _ in range(3):
        c.post("/predict", json={"url": BAD})
    assert count() == before + 3


def test_metrics_include_http_series(monkeypatch):
    c = _client(monkeypatch, "v1")
    c.post("/predict", json={"url": BAD})
    assert "http_requests_total" in c.get("/metrics").text
