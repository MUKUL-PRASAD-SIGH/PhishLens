import csv
import json
import subprocess
import sys
from pathlib import Path

import train

ROOT = Path(__file__).resolve().parents[1]


def test_synthetic_dataset_is_deterministic_and_balanced():
    a = train.synthetic_dataset(n=400, seed=1)
    assert a == train.synthetic_dataset(n=400, seed=1)
    positives = sum(label for _, label in a)
    assert 140 < positives < 260


def test_load_csv(tmp_path):
    p = tmp_path / "d.csv"
    p.write_text("url,label\nhttp://a.com,0\nhttp://1.2.3.4/x,1\n", encoding="utf-8")
    assert train.load_csv(p) == [("http://a.com", 0), ("http://1.2.3.4/x", 1)]


def test_metrics_perfect_and_worst():
    assert train.metrics([1, 0, 1, 0], [1, 0, 1, 0]) == {"accuracy": 1.0, "precision": 1.0, "recall": 1.0}
    assert train.metrics([1, 0, 1, 0], [0, 1, 0, 1])["accuracy"] == 0.0


def test_train_cli_on_csv_writes_model_and_metrics(tmp_path):
    rows = train.synthetic_dataset(n=300, noise=0.0, seed=3)
    data = tmp_path / "urls.csv"
    with open(data, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["url", "label"])
        w.writerows(rows)
    out, met = tmp_path / "m.pkl", tmp_path / "m.json"
    subprocess.run([sys.executable, "train.py", "--data", str(data), "--out", str(out), "--metrics", str(met)],
                   cwd=ROOT, check=True, capture_output=True)
    assert out.exists()
    result = json.loads(met.read_text())
    assert result["samples"] == 300 and result["v2"]["accuracy"] > 0.9


def test_committed_metrics_match_model_card():
    m = json.loads((ROOT / "model" / "metrics.json").read_text())
    assert m["v2"]["accuracy"] > m["v1"]["accuracy"]
    card = (ROOT / "model" / "MODEL_CARD.md").read_text(encoding="utf-8")
    assert str(m["v2"]["accuracy"]) in card and str(m["v1"]["recall"]) in card
