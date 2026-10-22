import os

import pytest

from app.scorer import Scorer

MODEL = os.path.join(os.path.dirname(__file__), "..", "model", "model.pkl")


@pytest.mark.skipif(not os.path.exists(MODEL), reason="run train.py first")
def test_v2_scores_phishing_above_legit():
    s = Scorer("v2", MODEL)
    assert s.loaded
    bad, _ = s.score("http://192.168.10.5/secure-login/verify@account-update.xyz")
    good, _ = s.score("https://www.github.com/docs")
    assert bad > 0.7 > 0.4 > good
