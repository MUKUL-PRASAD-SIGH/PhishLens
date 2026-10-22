# Model Card — PhishLens risk-model

## Versions

| Version | What it is | Select with |
|---|---|---|
| v1 | Hand-weighted heuristic (`app/scorer.py`, `V1_WEIGHTS`). No training. | `MODEL_VERSION=v1` |
| v2 | Logistic regression on 9 URL features, with feature scaling (`model/model.pkl`). | `MODEL_VERSION=v2` |

Features: URL length, digit count, special-character count, IP host, `@` symbol, subdomain count, suspicious TLD, HTTPS, Shannon entropy.

## Training data

**Synthetic, not real.** `train.py` generates 4,000 template-based URLs (50% phishing patterns: IP hosts, brand-lookalike on cheap TLDs, `@` tricks, subdomain stuffing; 50% legitimate: common hosts and paths), with 5% label noise. No external dataset or license is involved.

To retrain on real data (for example a Kaggle or PhishTank export, checking its license first), save a CSV with columns `url,label` (1 = phishing) and run `python train.py --data urls.csv`. Do not commit the raw dataset.

## Results (same 25% held-out split, 1,000 URLs)

| Model | Accuracy | Precision | Recall |
|---|---|---|---|
| v1 (flag if score ≥ 0.40) | 0.774 | 0.945 | 0.584 |
| v2 | 0.958 | 0.956 | 0.960 |

Raw numbers are in `model/metrics.json`.

## Limits

- The test split comes from the same synthetic generator as the training data, so these numbers show that v2 learned the patterns, not how it performs on real-world URLs. Expect lower accuracy on a real dataset.
- Features are lexical only. The model does not look up domain age, reputation, page content or redirects.
- Treat the score as a risk signal, not a guarantee.
- `scikit-learn` is pinned to `1.7.2` in `requirements.txt` for both training and serving. A different version may fail to load `model.pkl`.
