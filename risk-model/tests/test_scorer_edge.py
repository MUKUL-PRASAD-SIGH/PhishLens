import os

import pytest

from app.features import extract_features
from app.scorer import Scorer, score_v1

MODEL = os.path.join(os.path.dirname(__file__), "..", "model", "model.pkl")
BAD = "http://192.168.10.5/secure-login/verify@account-update.xyz"
GOOD = "https://www.github.com/docs"


def test_v1_clean_https_site_scores_low():
    assert score_v1(extract_features("https://example.com")) < 0.2


@pytest.mark.parametrize("flag_url", [
    "http://1.2.3.4/a",
    "http://a.com/x@y",
    "http://shop.example.xyz",
])
def test_each_signal_raises_score_over_baseline(flag_url):
    assert score_v1(extract_features(flag_url)) > score_v1(extract_features("https://example.com"))


def test_http_scores_higher_than_https_for_same_host():
    assert score_v1(extract_features("http://example.com")) > score_v1(extract_features("https://example.com"))


def test_subdomain_bonus_is_capped_at_three():
    base = extract_features("https://example.com")
    assert score_v1({**base, "num_subdomains": 3}) == score_v1({**base, "num_subdomains": 30})
    assert score_v1({**base, "num_subdomains": 3}) > score_v1({**base, "num_subdomains": 2})


def test_score_is_rounded_to_two_decimals():
    s, _ = Scorer("v1", "unused").score(BAD)
    assert s == round(s, 2)


def test_v1_scorer_always_loaded():
    assert Scorer("v1", "/does/not/matter").loaded is True


def test_unknown_version_without_model_is_not_loaded():
    assert Scorer("v9", "/missing").loaded is False


def test_v2_orders_bad_above_good_and_stays_in_range():
    s = Scorer("v2", MODEL)
    assert s.loaded
    assert s.score(BAD)[0] > s.score(GOOD)[0]
    for url in (BAD, GOOD, "", "a" * 2000):
        assert 0.0 <= s.score(url)[0] <= 1.0
