"""URL feature extraction shared by the v1 heuristic, training and the API."""
import math
import re
from collections import Counter
from urllib.parse import urlparse

SUSPICIOUS_TLDS = ("xyz", "top", "tk", "ml", "ga", "cf", "gq", "click", "work", "zip", "country", "support")
_TLD_RE = re.compile(r"\.(?:" + "|".join(SUSPICIOUS_TLDS) + r")(?=[/:?#]|$)", re.IGNORECASE)
_IPV4_RE = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")
_SPECIAL = set("-_~%=&?#+!$*,;")

# Order matters: the trained model expects this exact vector layout.
FEATURE_NAMES = (
    "length", "num_digits", "num_special", "has_ip_host", "has_at_symbol",
    "num_subdomains", "suspicious_tld", "uses_https", "entropy",
)
# Keys returned by /predict (see docs/SPEC.md section 3.2).
CONTRACT_FEATURES = (
    "length", "num_digits", "has_ip_host", "has_at_symbol",
    "num_subdomains", "suspicious_tld", "uses_https", "entropy",
)


def normalize(url: str) -> str:
    """Accept URLs typed without a scheme, e.g. 'example.com/login'."""
    url = url.strip()
    return url if "://" in url else "http://" + url


def _entropy(text: str) -> float:
    if not text:
        return 0.0
    n = len(text)
    return -sum(c / n * math.log2(c / n) for c in Counter(text).values())


def extract_features(url: str) -> dict:
    raw = url.strip()
    parsed = urlparse(normalize(raw))
    host = (parsed.hostname or "").lower()
    is_ip = bool(_IPV4_RE.match(host))
    labels = [l for l in host.split(".") if l]
    return {
        "length": len(raw),
        "num_digits": sum(ch.isdigit() for ch in raw),
        "num_special": sum(ch in _SPECIAL for ch in raw),
        "has_ip_host": is_ip,
        "has_at_symbol": "@" in raw,
        "num_subdomains": 0 if is_ip else max(len(labels) - 2, 0),
        "suspicious_tld": bool(_TLD_RE.search(raw)),
        "uses_https": parsed.scheme == "https",
        "entropy": round(_entropy(raw), 2),
    }


def to_vector(features: dict) -> list:
    return [float(features[name]) for name in FEATURE_NAMES]
