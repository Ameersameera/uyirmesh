# UyirMesh SOS priority model

Trained 2026-11-03 with scikit-learn 1.9.1.

## Data

- 20000 synthetic SOS messages (no public dataset of mesh SOS messages exists).
- Labels from a weighted triage policy: life threat (fire, medical, trapped, injured) first, then
  vulnerable people (children, elderly, disabled, pregnant), group size, urgent needs and low battery,
  with random measurement noise and 3% label disagreement.
- Class counts: {'Low': 2152, 'Medium': 6034, 'High': 7205, 'Critical': 4609}
- Split: 15000 training / 5000 test (stratified).

## Model

Random Forest, 30 trees, max depth 8, min 4 samples per leaf, 17 features.

## Results on the test set

- Accuracy: 80.1%
- Within one class: 99.8%

Confusion matrix (rows = true, columns = predicted; Low, Medium, High, Critical):

```
[[ 460   75    3    0]
 [ 109 1228  170    2]
 [   1  234 1387  179]
 [   0    4  216  932]]
```

```
              precision    recall  f1-score   support

         Low      0.807     0.855     0.830       538
      Medium      0.797     0.814     0.805      1509
        High      0.781     0.770     0.776      1801
    Critical      0.837     0.809     0.823      1152

    accuracy                          0.801      5000
   macro avg      0.806     0.812     0.809      5000
weighted avg      0.802     0.801     0.801      5000

```

## Feature importance

- injured: 0.344
- kind_need: 0.192
- children: 0.075
- need_medicine: 0.072
- people: 0.059
- kind_flood: 0.042
- elderly: 0.038
- need_boat: 0.034
- battery: 0.032
- kind_medical: 0.022
- pregnant: 0.019
- kind_trapped: 0.016
- disabled: 0.016
- kind_fire: 0.015
- need_water: 0.009
- need_food: 0.008
- need_baby_food: 0.006

JSON model matches scikit-learn within 1.75e-07.
