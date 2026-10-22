from app.features import extract_features
from app.scorer import score_v1


def test_ip_host_is_flagged():
    assert extract_features("http://192.168.10.5/login")["has_ip_host"] is True
    assert extract_features("https://github.com")["has_ip_host"] is False


def test_at_symbol_and_suspicious_tld_in_path():
    f = extract_features("http://192.168.10.5/secure-login/verify@account-update.xyz")
    assert f["has_at_symbol"] and f["suspicious_tld"]


def test_scheme_is_optional():
    f = extract_features("example.com/login")
    assert f["uses_https"] is False and f["num_subdomains"] == 0


def test_https_and_subdomain_count():
    f = extract_features("https://a.b.example.com")
    assert f["uses_https"] is True and f["num_subdomains"] == 2


def test_longer_url_scores_higher():
    short = "http://example.com/a"
    long = "http://example.com/" + "a" * 120
    assert score_v1(extract_features(long)) > score_v1(extract_features(short))


def test_v1_separates_good_from_bad():
    good = score_v1(extract_features("https://www.google.com"))
    bad = score_v1(extract_features("http://192.168.10.5/secure-login/verify@account-update.xyz"))
    assert good < 0.40 and bad >= 0.70


def test_score_stays_in_range():
    assert 0.0 <= score_v1(extract_features("http://1.1.1.1/" + "9" * 500 + "@x.xyz")) <= 1.0
