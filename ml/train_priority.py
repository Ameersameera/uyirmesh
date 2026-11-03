"""
UyirMesh - SOS priority model (Random Forest).

No public dataset of offline-mesh SOS messages exists, so a synthetic dataset is generated:
realistic SOS messages are sampled and labelled with a weighted triage policy
(life threat first, then vulnerable people, group size, urgent needs and low battery),
with measurement noise and 3% label disagreement. A Random Forest learns this policy.

The trained forest is exported to JSON and evaluated on the phone (core/Priority.kt).

Usage:  python3 ml/train_priority.py
Writes: app/src/main/assets/priority_model.json
        core/src/test/resources/priority_model.json, core/src/test/resources/priority_vectors.json
        ml/model_report.md
"""
import datetime
import json
import os

import numpy as np
import sklearn
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix
from sklearn.model_selection import train_test_split

SEED = 42
N_SAMPLES = 20000
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

FEATURES = [
    "kind_trapped", "kind_medical", "kind_fire", "kind_flood", "kind_need",
    "people", "children", "elderly", "disabled", "pregnant", "injured",
    "need_water", "need_food", "need_medicine", "need_baby_food", "need_boat", "battery",
]
CLASSES = ["Low", "Medium", "High", "Critical"]
KINDS = ["TRAPPED", "MEDICAL", "FIRE", "FLOOD", "NEED"]
KIND_P = [0.25, 0.20, 0.10, 0.30, 0.15]
KIND_BASE = {"TRAPPED": 38, "MEDICAL": 40, "FIRE": 45, "FLOOD": 32, "NEED": 12}
INJURED_P = {"TRAPPED": 0.45, "MEDICAL": 0.70, "FIRE": 0.45, "FLOOD": 0.20, "NEED": 0.08}
NEED_P = {  # water, food, medicine, baby_food, boat
    "TRAPPED": [0.35, 0.25, 0.25, 0.08, 0.15],
    "MEDICAL": [0.20, 0.10, 0.65, 0.06, 0.05],
    "FIRE": [0.30, 0.10, 0.30, 0.05, 0.02],
    "FLOOD": [0.50, 0.40, 0.20, 0.12, 0.55],
    "NEED": [0.60, 0.60, 0.30, 0.20, 0.10],
}


def sample(rng):
    kind = KINDS[rng.choice(len(KINDS), p=KIND_P)]
    people = int(min(20, 1 + rng.poisson(1.8)))
    children = int(rng.binomial(people, 0.18))
    elderly = int(rng.binomial(people - children, 0.15))
    disabled = int(rng.binomial(people, 0.05))
    pregnant = int(rng.binomial(max(0, people - children), 0.04))
    injured = int(rng.random() < INJURED_P[kind])
    needs = [int(rng.random() < p) for p in NEED_P[kind]]
    battery = int(rng.integers(1, 101)) if rng.random() > 0.05 else -1
    return kind, people, children, elderly, disabled, pregnant, injured, needs, battery


def features(kind, people, children, elderly, disabled, pregnant, injured, needs, battery):
    onehot = [1 if kind == k else 0 for k in KINDS]
    b = 50 if battery < 0 else battery
    return onehot + [people, children, elderly, disabled, pregnant, injured] + needs + [b]


def urgency(rng, kind, people, children, elderly, disabled, pregnant, injured, needs, battery):
    s = KIND_BASE[kind]
    s += 22 * injured
    s += min(18, 6 * children) + min(15, 5 * elderly) + min(18, 6 * disabled) + min(16, 8 * pregnant)
    s += min(15, 1.5 * (people - 1))
    water, food, medicine, baby, boat = needs
    s += 8 * medicine + 4 * water + 3 * food
    s += (7 if children > 0 else 5) * baby
    s += (10 if kind in ("FLOOD", "TRAPPED") else 7) * boat
    if 0 <= battery < 10:
        s += 8
    elif 0 <= battery < 25:
        s += 4
    return s + rng.normal(0, 3)


def label(score):
    if score >= 75:
        return 3
    if score >= 55:
        return 2
    if score >= 35:
        return 1
    return 0


def make_dataset(rng, n):
    X, y = [], []
    for _ in range(n):
        s = sample(rng)
        X.append(features(*s))
        c = label(urgency(rng, *s))
        if rng.random() < 0.03:  # annotator disagreement: move to a neighbouring class
            c = int(np.clip(c + rng.choice([-1, 1]), 0, 3))
        y.append(c)
    return np.array(X, dtype=np.float64), np.array(y, dtype=np.int64)


def export_tree(est):
    t = est.tree_
    values = []
    for row in t.value[:, 0, :]:
        total = float(row.sum())
        values.append([round(float(v) / total, 6) if total > 0 else 0.0 for v in row])
    return {
        "left": [int(v) for v in t.children_left],
        "right": [int(v) for v in t.children_right],
        "feature": [int(v) if v >= 0 else -1 for v in t.feature],
        "threshold": [round(float(v), 6) for v in t.threshold],
        "value": values,
    }


def predict_json(model, x):
    acc = np.zeros(len(model["classes"]))
    for tree in model["trees"]:
        node = 0
        while tree["left"][node] >= 0:
            f = tree["feature"][node]
            node = tree["left"][node] if x[f] <= tree["threshold"][node] else tree["right"][node]
        acc += np.array(tree["value"][node])
    return acc / len(model["trees"])


def main():
    rng = np.random.default_rng(SEED)
    X, y = make_dataset(rng, N_SAMPLES)
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.25, random_state=SEED, stratify=y)

    clf = RandomForestClassifier(n_estimators=30, max_depth=8, min_samples_leaf=4, random_state=SEED, n_jobs=1)
    clf.fit(X_train, y_train)
    pred = clf.predict(X_test)
    acc = accuracy_score(y_test, pred)
    within_one = float(np.mean(np.abs(pred - y_test) <= 1))
    cm = confusion_matrix(y_test, pred, labels=[0, 1, 2, 3])
    report = classification_report(y_test, pred, labels=[0, 1, 2, 3], target_names=CLASSES, digits=3)
    importances = sorted(zip(FEATURES, clf.feature_importances_), key=lambda kv: -kv[1])

    model = {
        "format": "uyirmesh-forest-1",
        "features": FEATURES,
        "classes": CLASSES,
        "trees": [export_tree(e) for e in clf.estimators_],
        "meta": {
            "algorithm": "RandomForestClassifier",
            "n_estimators": 30, "max_depth": 8, "min_samples_leaf": 4,
            "train_samples": int(len(y_train)), "test_samples": int(len(y_test)),
            "test_accuracy": round(float(acc), 4), "within_one_class": round(within_one, 4),
            "class_counts": {CLASSES[i]: int((y == i).sum()) for i in range(4)},
            "sklearn": sklearn.__version__,
            "trained": datetime.date.today().isoformat(),
        },
    }

    # The JSON model (rounded to 6 decimals) must give the same answers as scikit-learn.
    proba = clf.predict_proba(X_test)
    max_diff = max(float(np.max(np.abs(predict_json(model, x) - p))) for x, p in zip(X_test[:500], proba[:500]))
    assert max_diff < 1e-4, max_diff

    vectors = []
    vrng = np.random.default_rng(7)
    for _ in range(60):
        s = sample(vrng)
        x = features(*s)
        vectors.append({
            "input": {
                "kind": s[0], "people": s[1], "children": s[2], "elderly": s[3], "disabled": s[4], "pregnant": s[5],
                "injured": bool(s[6]), "needs": [n for n, v in zip(["WATER", "FOOD", "MEDICINE", "BABY_FOOD", "BOAT"], s[7]) if v],
                "battery": s[8],
            },
            "x": x,
            "proba": [round(float(v), 8) for v in clf.predict_proba(np.array([x], dtype=np.float64))[0]],
        })

    text = json.dumps(model, separators=(",", ":"))
    for path in ["app/src/main/assets/priority_model.json", "core/src/test/resources/priority_model.json"]:
        full = os.path.join(ROOT, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as f:
            f.write(text)
    with open(os.path.join(ROOT, "core/src/test/resources/priority_vectors.json"), "w") as f:
        json.dump(vectors, f, indent=1)

    lines = [
        "# UyirMesh SOS priority model", "",
        f"Trained {model['meta']['trained']} with scikit-learn {sklearn.__version__}.", "",
        "## Data", "",
        f"- {N_SAMPLES} synthetic SOS messages (no public dataset of mesh SOS messages exists).",
        "- Labels from a weighted triage policy: life threat (fire, medical, trapped, injured) first, then",
        "  vulnerable people (children, elderly, disabled, pregnant), group size, urgent needs and low battery,",
        "  with random measurement noise and 3% label disagreement.",
        f"- Class counts: {model['meta']['class_counts']}",
        f"- Split: {len(y_train)} training / {len(y_test)} test (stratified).", "",
        "## Model", "",
        "Random Forest, 30 trees, max depth 8, min 4 samples per leaf, 17 features.", "",
        "## Results on the test set", "",
        f"- Accuracy: {acc * 100:.1f}%",
        f"- Within one class: {within_one * 100:.1f}%", "",
        "Confusion matrix (rows = true, columns = predicted; Low, Medium, High, Critical):", "",
        "```", str(cm), "```", "",
        "```", report, "```", "",
        "## Feature importance", "",
    ] + [f"- {name}: {imp:.3f}" for name, imp in importances] + [
        "", f"JSON model matches scikit-learn within {max_diff:.2e}.", "",
    ]
    with open(os.path.join(ROOT, "ml/model_report.md"), "w") as f:
        f.write("\n".join(lines))
    print("\n".join(lines))
    print("model bytes:", len(text))


if __name__ == "__main__":
    main()
