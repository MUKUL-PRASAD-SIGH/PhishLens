"""Train the v2 model and compare it with the v1 heuristic on the same test split.

Usage:
    python train.py                      # synthetic data (no download needed)
    python train.py --data urls.csv      # real data: columns `url,label` (label 1 = phishing)
"""
import argparse
import csv
import json
import random

import joblib
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, precision_score, recall_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from app.features import extract_features, to_vector
from app.scorer import score_v1

LEGIT_HOSTS = ["google.com", "github.com", "wikipedia.org", "amazon.in", "microsoft.com", "python.org",
               "stackoverflow.com", "bbc.co.uk", "nytimes.com", "apple.com", "linkedin.com", "iitd.ac.in"]
LEGIT_PATHS = ["", "/", "/about", "/docs/getting-started", "/search?q=devops", "/products/item-42",
               "/blog/2026/10/release-notes", "/login", "/account/settings"]
BRANDS = ["paypal", "amazon", "netflix", "apple", "sbi", "hdfc", "microsoft", "google"]
BAD_TLDS = ["xyz", "top", "tk", "ml", "click", "work", "zip", "support"]
BAD_WORDS = ["secure", "verify", "login", "update", "account", "confirm", "billing"]


def _rand_token(rng, n):
    return "".join(rng.choice("abcdefghijklmnopqrstuvwxyz0123456789") for _ in range(n))


def _legit(rng):
    host = rng.choice(LEGIT_HOSTS)
    if rng.random() < 0.3:
        host = "www." + host
    scheme = "https" if rng.random() < 0.9 else "http"
    return f"{scheme}://{host}{rng.choice(LEGIT_PATHS)}"


def _phish(rng):
    kind = rng.random()
    word, brand, tld = rng.choice(BAD_WORDS), rng.choice(BRANDS), rng.choice(BAD_TLDS)
    scheme = "http" if rng.random() < 0.75 else "https"
    if kind < 0.3:  # raw IP host
        ip = ".".join(str(rng.randint(1, 254)) for _ in range(4))
        return f"{scheme}://{ip}/{word}-{brand}/{_rand_token(rng, rng.randint(4, 12))}"
    if kind < 0.6:  # brand lookalike on a cheap TLD
        return f"{scheme}://{brand}-{word}.{tld}/{word}/{_rand_token(rng, 8)}"
    if kind < 0.8:  # '@' trick
        return f"{scheme}://{brand}.com@{word}-{_rand_token(rng, 6)}.{tld}/{word}"
    sub = ".".join(rng.choice(BAD_WORDS) for _ in range(rng.randint(2, 4)))  # subdomain stuffing
    return f"{scheme}://{sub}.{brand}-{_rand_token(rng, 5)}.com/{word}?id={rng.randint(1000, 99999)}"


def synthetic_dataset(n=4000, noise=0.05, seed=42):
    """Template-generated URLs; `noise` flips labels to mimic real-world ambiguity."""
    rng = random.Random(seed)
    rows = []
    for _ in range(n):
        label = rng.random() < 0.5
        url = _phish(rng) if label else _legit(rng)
        if rng.random() < noise:
            label = not label
        rows.append((url, int(label)))
    return rows


def load_csv(path):
    with open(path, newline="", encoding="utf-8") as fh:
        return [(r["url"], int(r["label"])) for r in csv.DictReader(fh)]


def metrics(y_true, y_pred):
    return {
        "accuracy": round(accuracy_score(y_true, y_pred), 3),
        "precision": round(precision_score(y_true, y_pred), 3),
        "recall": round(recall_score(y_true, y_pred), 3),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", help="CSV with columns url,label")
    ap.add_argument("--out", default="model/model.pkl")
    ap.add_argument("--metrics", default="model/metrics.json")
    args = ap.parse_args()

    rows = load_csv(args.data) if args.data else synthetic_dataset()
    feats = [extract_features(u) for u, _ in rows]
    X, y = [to_vector(f) for f in feats], [label for _, label in rows]
    idx = list(range(len(rows)))
    tr, te = train_test_split(idx, test_size=0.25, random_state=42, stratify=y)

    model = make_pipeline(StandardScaler(), LogisticRegression(max_iter=1000))
    model.fit([X[i] for i in tr], [y[i] for i in tr])

    y_test = [y[i] for i in te]
    v2 = metrics(y_test, model.predict([X[i] for i in te]))
    v1 = metrics(y_test, [int(score_v1(feats[i]) >= 0.40) for i in te])
    result = {"dataset": args.data or "synthetic", "samples": len(rows), "test_samples": len(te), "v1": v1, "v2": v2}

    joblib.dump(model, args.out)
    with open(args.metrics, "w") as fh:
        json.dump(result, fh, indent=2)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
