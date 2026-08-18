# Fleur benchmark corpus

A growing set of semi-realistic CWL tools and workflows used as a **development
benchmark**. The point is to have a repertoire of cases — including ones Fleur
does **not** fully support yet — and measure Fleur against them as features land.

## How it works

Every case is listed in [`manifest.edn`](manifest.edn) and run by the test
namespace [`fleur.benchmark-test`](../test/fleur/benchmark_test.clj), which
executes each CWL document (via `fleur.process/run-file`) and compares the bound
outputs against the case's `:expected`.

Each case has a `:status`:

| Status         | Meaning                                                                                   | Test behavior                                                                 |
| -------------- | ----------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| `:supported`   | Fleur should produce `:expected`.                                                         | **Fails** if the output doesn't match — a real regression guard.             |
| `:unsupported` | Exercises a CWL feature Fleur doesn't implement yet. `:expected` is the *correct* output. | Asserts the output does **not** match yet, so the suite stays green.         |

The `:unsupported` convention makes the manifest an **executable roadmap**: while
a feature is missing the case stays green; the moment Fleur starts producing the
correct output, the `not=` assertion fails and tells you to promote the case to
`:supported`. So implementing a feature naturally turns a red test green (after a
one-line manifest edit).

## Running it

```bash
# As part of the whole suite
clojure -X:test

# Just the benchmark
clojure -X:test :nses '[fleur.benchmark-test]'
```

The run prints a report, e.g.:

```
=== Fleur benchmark corpus ===
  expr-double            expression-tool supported    = match
  scatter-simple         scatter         unsupported  ! mismatch -> {:doubled ##NaN}
  ...
Supported: 2   Unsupported: 5   Total: 7
```

## Current cases

| Case                   | Feature                    | Status      |
| ---------------------- | -------------------------- | ----------- |
| `expr-double`          | ExpressionTool             | supported   |
| `linear-math`          | linear Workflow            | supported   |
| `diamond-dag`          | non-linear static DAG      | supported   |
| `step-valuefrom`       | step-input `valueFrom`     | supported   |
| `input-default`        | input `default`            | supported   |
| `cat-concat`           | CommandLineTool (stdout/glob) | supported |
| `echo-arguments`       | `arguments` + `valueFrom`  | supported   |
| `scatter-simple`       | scatter                    | unsupported |
| `scatter-dotproduct`   | scatter (dotproduct)       | unsupported |
| `scatter-crossproduct` | scatter (flat_crossproduct) | unsupported |
| `when-skip`            | conditional `when`         | unsupported |
| `linkmerge-flattened`  | multi-source `linkMerge` (flattened) | unsupported |
| `linkmerge-nested`     | multi-source `linkMerge` (nested) | unsupported |
| `subworkflow-inline`   | inline sub-`Workflow` run  | unsupported |
| `load-contents`        | File `loadContents`        | unsupported |
| `wc-lines-eval`        | scalar output via `outputEval` | unsupported |
| `env-var`              | `EnvVarRequirement`        | unsupported |

The corpus targets the known gaps from `CLAUDE.md`'s roadmap — scatter/gather,
conditional `when`, `linkMerge`, `loadContents` — alongside supported baselines
(ExpressionTools, linear/DAG workflows, real CommandLineTools, step `valueFrom`,
input defaults) that guard against regressions.

Building the corpus also surfaced three gaps not previously called out: inline
sub-`Workflow` steps aren't accepted (file-referenced ones are), scalar outputs
via `outputBinding` `loadContents`+`outputEval` report "Unsupported output type",
and `EnvVarRequirement` variables aren't set in the tool environment.

### Comparing File outputs

CommandLineTool cases usually produce File outputs. A case's optional `:project`
map reduces a File output to a comparable value before comparison:
`{:out :contents}` reads and trims the produced file's text; any other keyword
(e.g. `:basename`) pulls that field from the File map. Scalar/array outputs need
no projection.

## Adding a case

1. Drop the CWL (and any data files) under a feature folder in `benchmark/`.
   Prefer **hermetic** cases — `ExpressionTool`s or `echo`/`cat`
   `CommandLineTool`s producing simple, comparable outputs (ints, strings,
   arrays) — so no external binaries or large data are needed and the expected
   value is easy to pin.
2. Add an entry to `manifest.edn` with `:id`, `:feature`, `:status`,
   `:description`, `:cwl`, `:job` (inline input map), and `:expected` (a map of
   output-id → value; only these keys are compared). For File outputs, add a
   `:project` map (see above) so the comparison sees a scalar.
3. Run the benchmark. A new `:unsupported` case should report `! mismatch`/`!
   error`; a `:supported` case should report `= match`.
