# SPADE

Sequential pattern mining over CASAS smart-home sensor readings. Sensor state
changes become a symbol sequence, the span between a sensor switching on and
switching back off becomes an episode, and a context tree built over those
episodes holds the conditional probability of each event given the events that
preceded it.

## Running

Needs a JDK, nothing else.

```bash
javac *.java
java Spade              # reads canvas.csv
java Spade other.csv
```

On the shipped dataset:

```
Events: 1922 | contexts: 46 | tree nodes: 122784
Scan efficiency: 6.010382137719683%
Unigram top-1 accuracy: 4.630593132154006% (mean rank 16.64 of 46)
Context top-1 accuracy (in-sample): 88.13735691987513% (order <= 5, 1922 predictions)
Held-out top-1 accuracy: 54.8051948051948% context vs 4.675324675324675% unigram baseline (trained on 1537, scored on 385, order <= 5, min context count 1)
```

## Input format

`canvas.csv` has no header row. Columns 0 to 4 are the timestamp, columns 5 to
27 are the 23 sensors, and the last two columns are the activity label and the
occupancy flag, which the encoder skips.

A sensor counts as active when its value is `ON`, `PRESENT`, or a nonzero
number, and inactive when it is `OFF`, `ABSENT`, or `0.0`. Anything else, the
activity label for instance, is neither and produces no event.

## Encoding

Sensor column `i` emits `(char)(60 + i)` when it becomes active and the matching
lowercase letter when it goes inactive, so column 5 is `A`/`a` and column 27 is
`W`/`w`. The first row seeds the initial state, and every row after that emits
one symbol per sensor that changed.

The upper/lower pairing is what makes episodes findable, so a column that would
encode outside `A` to `Z` now throws instead of silently emitting punctuation
that `getEpisodes` would skip. That caps the encoding at 26 sensor columns.

## Episodes and the context tree

An episode runs from a sensor turning on to the first time that same sensor
turns off, which means episodes overlap and each one carries whatever other
sensors fired inside its window.

`genContext` inserts every suffix of every episode, so a path `root -> x -> y`
means `y` followed `x`, and a node's frequency counts how often that context
occurred. A node's probability is its frequency over its parent's
frequency, so it reads as P(event | context). Root children sum to 1. Deeper
levels sum to at most 1, because the last event of an episode has no successor
to pass the count along to.

On `canvas.csv` that produces 122,784 nodes reaching depth 164.

## Metrics

`Scan efficiency` is the original metric, matched events over the total nodes
scanned while ranking them, kept so the number stays comparable across commits.
It is a reciprocal mean rank, so higher is better, and earlier revisions
labelled it a correlation coefficient, which it is not.

`Unigram top-1 accuracy` only looks at the 46 root contexts, so it is the
frequency baseline and ignores the tree entirely.

`Context top-1 accuracy` walks the tree along the longest recent history the
tree has actually seen, up to order 5, and backs off toward the root whenever
the tree has never seen a longer context.

Those three are all in-sample, measuring fit rather than generalisation, so
`Held-out top-1 accuracy` builds the tree from the first 80% of the sequence and
scores the remaining 385 events, none of which contributed a count. History fed
to the predictor is still the real past, which a model would have available at
prediction time.

That split is what the numbers should be read off. The context model gets 54.8%
against 4.7% for always guessing the most frequent training event, so the tree
is worth roughly twelve times the baseline, and the drop from 88.1% in-sample to
54.8% held-out is the memorisation a suffix trie invites rather than a bug.

## What the tuning actually showed

Sweeping the two knobs over the held-out split, top-1 accuracy:

| order \ minCount | 1 | 2 | 4 | 8 | 16 | 32 |
|---|---|---|---|---|---|---|
| 1 | 56.10% | 56.10% | 56.10% | 56.10% | 56.10% | 56.10% |
| 2 | 53.51% | 53.51% | 53.51% | 53.51% | 54.29% | 54.81% |
| 3 | 55.32% | 55.32% | 55.32% | 55.06% | 55.84% | 55.32% |
| 5 | 54.81% | 54.81% | 55.06% | 55.06% | 55.06% | 54.81% |
| 8 | 55.58% | 55.58% | 55.84% | 56.10% | 55.32% | 55.06% |
| 10 | 55.06% | 55.06% | 55.58% | 56.10% | 55.32% | 55.06% |

The whole grid sits between 53.5% and 56.1%, and one scored event moves the
number by 0.26%, so nothing here separates from noise. Two things follow. Order 1
already captures essentially all of the transferable signal, meaning the deep
contexts help in-sample and not out of it. And `MIN_CONTEXT_COUNT` is a null
result on this data, so it ships at 1, switched off, rather than tuned to a value
the evidence doesn't support.

Anyone porting this to a longer capture should re-run the sweep before trusting
either default.

## Tests

```bash
javac *.java && java SpadeTest
```

61 assertions, no third-party dependencies, non-zero exit on failure. The
end-to-end cases pin the encoded sequence length, episode count, root frequency,
and node count to the values the pre-existing implementation produced, so a
change that alters the algorithm's output fails rather than passing quietly.

## Known limitations

- `getEpisodes` drops any episode whose sensor never switches off.
- Prediction contexts come from the flat sequence, so a context can span an
  episode boundary.
- `MAX_ORDER`, `MIN_CONTEXT_COUNT`, and `TRAIN_FRACTION` are constants in
  `Spade.java` rather than arguments, though `predict` and `evaluateHeldOut`
  both take them as parameters for sweeping.
- The held-out split is a single contiguous cut, not cross-validation, so 385
  scored events carry roughly a 2.5 point standard error.
- Rows shorter than the first row will throw on the sensor index.
