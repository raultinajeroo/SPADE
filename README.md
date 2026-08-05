# SPADE

Sequential pattern mining over CASAS smart-home sensor readings. Sensor state
changes become a symbol sequence, the span between a sensor switching on and
switching back off becomes an episode, and a context tree built over those
episodes holds the conditional probability of each event given the events that
preceded it.

## Running

Needs a JDK, nothing else. Compile with UTF-8 because the event alphabet reaches
past Latin.

```bash
javac -encoding UTF-8 *.java
java Spade              # reads canvas.csv
java Spade other.csv
```

On the shipped dataset:

```
Events: 1922 | contexts: 46 | tree nodes: 122784
Scan efficiency: 6.010382137719683%
Unigram top-1 accuracy: 4.630593132154006% (mean rank 16.64 of 46)
Context top-1 accuracy (in-sample): 61.49843912591051% (order <= 1, 1922 predictions)
Held-out top-1 accuracy: 63.37662337662338% context vs 9.090909090909092% baseline (trained on 1537, scored on 385, order <= 1, min count 1, legal-move mask on)
```

## Input format

`canvas.csv` has no header row. Columns 0 to 4 are the timestamp, columns 5 to
27 are the 23 sensors, and the last two columns are the activity label and the
occupancy flag, which the encoder skips.

A sensor counts as active when its value is `ON`, `PRESENT`, or a nonzero
number, and inactive when it is `OFF`, `ABSENT`, or `0.0`. Anything else, the
activity label for instance, is neither and produces no event. Note that raw
CASAS door sensors report `OPEN` and `CLOSE`, which match neither, so the
converter below folds them onto the presence vocabulary.

## Encoding

Sensor column `i` emits a capital letter when it becomes active and the matching
lowercase letter when it goes inactive, so column 5 is `A`/`a` and column 27 is
`W`/`w`. The first row seeds the initial state, and every row after that emits
one symbol per sensor that changed.

Episodes pair a sensor's two events by letter case, so every symbol needs a
distinct lowercase form. Latin runs out at 26 sensors, which the CASAS error set
exceeds at 28, so the alphabet continues into Greek and Cyrillic capitals for a
total of 82. The first 26 symbols are still `A` to `Z`, so anything encoded
before the alphabet widened encodes identically now. A column past the end of
the alphabet throws rather than silently emitting a symbol `getEpisodes` would
skip.

## Episodes and the context tree

An episode runs from a sensor turning on to the first time that same sensor
turns off, which means episodes overlap and each one carries whatever other
sensors fired inside its window.

`genContext` inserts every suffix of every episode, so a path `root -> x -> y`
means `y` followed `x`, and a node's frequency counts how often that context
occurred. A node's probability is its frequency over its parent's frequency, so
it reads as P(event | context). Root children sum to 1. Deeper levels sum to at
most 1, because the last event of an episode has no successor to pass the count
along to.

On `canvas.csv` that produces 122,784 nodes reaching depth 164.

## Prediction

`predict` walks the tree along the longest recent history it has seen, up to
`MAX_ORDER`, backing off toward the root whenever the tree has never seen a
longer context.

`filterImpossible` then screens that ranking against what can physically happen. A
sensor alternates, so it cannot switch on twice without switching off in
between, which means the prefix fixes every sensor's current state and only the
symbol opposite that state can come next. Everything else was never a candidate
whatever the tree ranked it. That mask is worth 8 to 10 points and is the single
largest gain in here.

## Metrics

`Scan efficiency` is the original metric, matched events over the total nodes
scanned while ranking them, kept so the number stays comparable across commits.
It is a reciprocal mean rank, so higher is better, and earlier revisions
labelled it a correlation coefficient, which it is not.

`Unigram top-1 accuracy` only looks at the root contexts, so it is the frequency
baseline and ignores the tree entirely.

`Context top-1 accuracy` is the tree plus the legal-move mask, scored in-sample.

`Held-out top-1 accuracy` builds the tree from the first 80% of the sequence and
scores the rest, none of which contributed a count. History fed to the predictor
is still the real past, which a model would have available at prediction time.

That split is what the numbers should be read off. The context model gets 63.4%
against 9.1% for always guessing the most likely legal event.

## What the tuning actually showed

Two knobs were swept over held-out splits on both CASAS datasets, at five train
fractions each. Mean held-out top-1:

| | order 1 | order 2 | order 3 | order 5 |
|---|---|---|---|---|
| canvas.csv, mask off | 53.24% | 52.24% | 52.60% | 52.32% |
| canvas.csv, mask on | **60.98%** | 58.99% | 59.77% | 56.84% |
| adl_error.csv, mask off | 51.55% | 51.20% | 51.20% | 50.85% |
| adl_error.csv, mask on | **61.39%** | 57.45% | 56.90% | 56.01% |

Three results, all replicating across both datasets and every train fraction:

The legal-move mask helps everywhere, by 8 to 10 points, and never hurts. Order 1
beats every deeper order, winning 10 comparisons out of 10, so the deep contexts
lift the in-sample score and lose held out. And `MIN_CONTEXT_COUNT` is a null
result, moving held-out top-1 by under 2 points across 1 to 32, so it ships at 1,
switched off, rather than tuned to a value the evidence doesn't support.

Worth noting what closing the gap looks like: before this, `canvas.csv` scored
88.1% in-sample against 54.8% held out, and it now scores 61.5% against 63.4%.
The model stopped memorising and started generalising, and the headline in-sample
number went down as a result.

Anyone porting this to a longer capture should re-run the sweep before trusting
the defaults, since 385 scored events carry roughly a 2.5 point standard error.

## Converting CASAS data

`canvas.csv` is a preprocessed slice of the CASAS scripted-ADL set, confirmed by
its five activities, its 2008-02-27 to 2008-05-21 date range, and its 120
activity runs matching the 120 session files one for one.

CASAS ships one sensor event per row rather than the wide per-minute matrix
Spade reads, so the converter buckets events by minute:

```bash
uv run tools/casas_to_canvas.py path/to/adl_error /tmp/adl_error.csv
java Spade /tmp/adl_error.csv
```

Each minute records whether a sensor showed activity in it, not the state it
happened to end on, since a motion sensor that fires and clears inside the same
minute still means somebody walked past. Item sensors idle at `PRESENT` and
report `ABSENT` when someone lifts the item, doors fold `OPEN` and `CLOSE` onto
that same vocabulary, and the converter drops the `E01` channel because it
carries experiment markers rather than sensor state.

The converter reproduces `canvas.csv`'s shape exactly, 23 sensors in the same
grouping and order across 120 sessions, but not byte for byte. The residual is
the door column, which `canvas.csv` varies across minutes where `D01` has no
events at all in this release, so that column appears to come from a different
preprocessing pass than the one published here.

## Tests

```bash
javac -encoding UTF-8 *.java && java SpadeTest
```

65 assertions, no third-party dependencies, non-zero exit on failure. The
end-to-end cases pin the encoded sequence length, episode count, root frequency,
and node count to the values the pre-existing implementation produced, so a
change that alters the algorithm's output fails rather than passing quietly.

## Known limitations

- `getEpisodes` drops any episode whose sensor never switches off.
- Prediction contexts come from the flat sequence, so a context can span an
  episode boundary.
- `MAX_ORDER`, `MIN_CONTEXT_COUNT`, and `TRAIN_FRACTION` are constants in
  `Spade.java`, though `predict` and `evaluateHeldOut` both take them as
  parameters for sweeping.
- The held-out split is a single contiguous cut, not cross-validation.
- Rows shorter than the first row will throw on the sensor index.
- The tree is still built to full depth even though prediction defaults to
  order 1, which costs memory that nothing currently reads.
