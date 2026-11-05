import pytest

from app.features import CONTRACT_FEATURES, FEATURE_NAMES, extract_features, normalize, to_vector


@pytest.mark.parametrize("url,expected", [
    ("example.com", "http://example.com"),
    ("  example.com  ", "http://example.com"),
    ("https://example.com", "https://example.com"),
    ("ftp://example.com", "ftp://example.com"),
])
def test_normalize(url, expected):
    assert normalize(url) == expected


@pytest.mark.parametrize("host,is_ip", [
    ("http://1.2.3.4", True),
    ("http://255.255.255.255/x", True),
    ("http://1.2.3", False),
    ("http://example.com", False),
    ("http://1.2.3.4.example.com", False),
])
def test_ip_host_detection(host, is_ip):
    assert extract_features(host)["has_ip_host"] is is_ip


@pytest.mark.parametrize("tld,flag", [("xyz", True), ("TOP", True), ("zip", True), ("com", False), ("org", False)])
def test_suspicious_tld(tld, flag):
    assert extract_features(f"http://shop.example.{tld}/a")["suspicious_tld"] is flag


def test_tld_must_end_the_host_not_a_label():
    assert extract_features("http://xyz.example.com")["suspicious_tld"] is False


def test_ip_host_has_no_subdomains():
    assert extract_features("http://10.0.0.1/a")["num_subdomains"] == 0


def test_counts_digits_and_length_on_raw_url():
    f = extract_features("http://a1b22.com/333")
    assert f["num_digits"] == 6
    assert f["length"] == len("http://a1b22.com/333")


def test_special_characters_counted():
    assert extract_features("http://a.com/a-b_c?x=1&y=2")["num_special"] == 6


def test_entropy_is_higher_for_random_looking_urls():
    assert extract_features("a")["entropy"] < extract_features("http://x9q-Zt3.example.com/Kd8")["entropy"]


def test_empty_url_does_not_crash():
    f = extract_features("")
    assert f["length"] == 0 and f["has_ip_host"] is False and f["entropy"] >= 0


def test_vector_matches_feature_order():
    f = extract_features("https://www.example.com/a1")
    assert to_vector(f) == [float(f[n]) for n in FEATURE_NAMES]
    assert len(to_vector(f)) == 9


def test_contract_features_are_subset_of_model_features():
    assert set(CONTRACT_FEATURES) < set(FEATURE_NAMES)
