# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Fleur is a Clojure library for running Common Workflow Language (CWL) workflows. It currently provides functionality to parse, execute, and manage CWL CommandLineTools and ExpressionTools, with support for Docker containers and schema validation via schema-salad. It can also run static-DAG `Workflow` processes with scatter/gather; conditional (`when`) execution is not yet implemented. Though most of that functionality is currently incomplete and not thoroughly tested.

## Architecture

The codebase is organized into these namespaces:

- `fleur.command-line-tool`: Core functionality for parsing CWL tools, binding inputs/outputs, and executing commands
- `fleur.expression`: Evaluation of CWL parameter references (`$(...)`) and
  JavaScript expressions (`$(...)`/`${...}`). Parameter references use a
  pure-Clojure walker; JavaScript is evaluated with Mozilla Rhino (ECMAScript
  5.1) and only when `InlineJavascriptRequirement` is in effect.
- `fleur.runtime`: Constructs the CWL `runtime` object (`outdir`, `tmpdir`,
  `cores`, `ram`, ...) exposed to expressions as `runtime.*`, honoring
  `ResourceRequirement`.
- `fleur.staging`: Resolves input File/Directory paths to absolute locations
  (populating CWL File metadata: `basename`, `dirname`, `nameroot`, `nameext`,
  `size`), stages inputs into the working directory, and processes
  `InitialWorkDirRequirement`.
- `fleur.preprocess`: Backend-swappable CWL document preprocessing API. The
  default `:cwljava` backend uses the schema-salad-generated Java SDK (loaded
  reflectively) for full-fidelity loading — `$import`/`$graph`, identifier/link
  resolution, type/secondaryFiles DSL — and adapts its object graph back into
  Fleur maps. A dependency-light native `:clojure` backend implements a pragmatic
  subset (type-DSL expansion + inputs/outputs normalization; errors on
  `$import`/`$graph`), and `:schema-salad-tool` shells out to the Python tool.
- `fleur.schema-salad`: Integration with schema-salad-tool (the
  `:schema-salad-tool` preprocessing backend) for full-fidelity CWL preprocessing.
- `fleur.docker`: Docker execution — image resolution/pull, planning read-only
  input mounts + a read-write outdir/tmpdir mount, remapping input File paths to
  their in-container locations, and assembling the `docker run` argv.
- `fleur.expression-tool`: Execution of `ExpressionTool` processes — evaluate
  the tool's `expression` against the bound inputs and map the resulting object
  onto the declared outputs.
- `fleur.process`: Uniform entry point that runs a CWL process by dispatching on
  its `class` (`CommandLineTool` / `ExpressionTool` / `Workflow`). `run` takes an
  already-parsed process; `run-file` preprocesses a CWL file (cwljava by default)
  and then runs it — the end-to-end entry point for a document on disk.
- `fleur.workflow`: Execution of linear (static-DAG) `Workflow` processes —
  models step dependencies as an ubergraph digraph, runs steps in topological
  order (rejecting cycles), wires each step's outputs into downstream inputs and
  the workflow outputs, inherits requirements from the workflow onto steps, and
  resolves each step's `run` (an inline process, or a CWL file reference —
  including the `file://` URIs cwljava produces — loaded via `fleur.preprocess`).
- `fleur.main`: `cwl-runner`-style command-line entry point (`-main`,
  AOT-compiled into the uberjar). Parses args, loads a job file, runs the
  document via `fleur.process/run-file`, and prints the bound outputs as JSON.

The workflow involves:
1. Preprocessing CWL files with schema-salad-tool to resolve references and validate schema
2. Parsing the preprocessed CWL into Clojure data structures
3. Binding input values and default values to tool inputs
4. Building command lines from the tool specification and sorted inputs
5. Executing commands via `clojure.java.shell`
6. Handling Docker requirements when specified

## Development Commands

### Editor / AI tooling (clojure-mcp-light)
Optional CLI helpers for Clojure + AI assistants (nREPL eval, delimiter repair).
Not preinstalled in web/remote sessions — reinstall with:
```bash
bash scripts/setup-clojure-mcp-light.sh && export PATH="$PATH:$HOME/.local/bin"
```
See `docs/dev-setup/clojure-mcp-light.md` for details (including the proxy TLS
workaround needed for Babashka).

### REPL Development
```bash
# Start REPL with dev dependencies
clj -M:dev:nrepl

# Start REPL with Portal for data visualization
clj -M:dev -e "(require 'portal.api) (portal.api/start)"
```

### Building the CLI uberjar
The `fleur.main` namespace is a `cwl-runner`-style CLI. Build a standalone jar
with tools.build:
```bash
clojure -T:build uber      # -> target/cwl-runner-<version>-standalone.jar
```
Run it directly, or via the wrapper in `bin/cwl-runner` (put it on your PATH):
```bash
java -jar target/cwl-runner-0.1.0-standalone.jar <document.cwl> [<job.json|job.yml>]

ln -s "$(pwd)/bin/cwl-runner" ~/.local/bin/cwl-runner
cwl-runner resources/linear_math.cwl job.json      # prints the outputs as JSON
```
The document is preprocessed (cwljava by default) and run; the bound outputs
are written to stdout as a JSON object. `bin/cwl-runner` finds the jar via
`$FLEUR_JAR` or the newest `target/cwl-runner-*-standalone.jar`. Options:
`--outdir DIR`, `--backend {cwljava,clojure,schema-salad-tool}`, `--help`,
`--version`.

### Testing
Tests use `clojure.test` and run via the Cognitect test-runner:
```bash
# Run the whole suite
clojure -X:test

# Run a single namespace
clojure -X:test :nses '[fleur.command-line-tool-test]'
```
Test files live in `test/`: `fleur.command-line-tool-test`,
`fleur.expression-test`, `fleur.expression-tool-test`, `fleur.preprocess-test`,
`fleur.process-test`, `fleur.workflow-test`, `fleur.runtime-test`,
`fleur.staging-test`, `fleur.docker-test`, `fleur.cwljava-test`,
`fleur.pipeline-test`, and `fleur.benchmark-test`. All should
stay green. `fleur.cwljava-test` exercises the default `:cwljava` backend and
runs as part of the normal suite (cwljava is a regular dependency).

`fleur.docker-test`'s end-to-end container test is guarded: it runs only when a
Docker daemon is reachable and the `busybox:latest` image is already present,
and otherwise skips (so the suite passes without Docker). To exercise it, start
a daemon and pull the image first:
```bash
dockerd >/tmp/dockerd.log 2>&1 &   # if no daemon is running
docker pull busybox:latest
```

### Benchmark corpus
`benchmark/` holds a growing set of semi-realistic CWL tools/workflows used as a
development benchmark, driven by `fleur.benchmark-test` from
`benchmark/manifest.edn`. Each case is `:supported` (must match its `:expected`
output — a regression guard) or `:unsupported` (exercises a not-yet-implemented
CWL feature; `:expected` is the correct spec output and the test asserts Fleur
does **not** match yet, so the suite stays green until the feature lands and then
turns red — the cue to promote the case). This makes the manifest an executable
roadmap; the initial batch targets the known gaps (scatter, conditional `when`,
`linkMerge`, `loadContents`). See `benchmark/README.md` (incl. how to add a case).

## Dependencies

- **schema-salad**: External tool required for CWL preprocessing (installed via conda)
- **Docker**: Required for executing tools with Docker requirements
- **Core Clojure libraries**: data.json for JSON parsing, clj-yaml for YAML support
- **Rhino** (`org.mozilla/rhino`): pure-Java ECMAScript engine used by
  `fleur.expression` to evaluate CWL JavaScript expressions on the JVM
- **ubergraph** (`ubergraph/ubergraph`): directed-graph library used by
  `fleur.workflow` for step-dependency ordering, cycle detection, and (optional)
  visualization
- **cwljava** (`com.github.common-workflow-language/cwljava`): the
  schema-salad-generated CWL Java SDK (`org.commonwl.cwlsdk.cwl1_2`), pulled from
  GitHub via JitPack (`:mvn/repos`). Powers the default `:cwljava` preprocessing
  backend. Loaded reflectively, so it stays a runtime/classpath dependency with
  no compile-time coupling.

## Key Data Structures

CWL tools are represented as Clojure maps with the structure:
- `:baseCommand`: The base command to execute
- `:inputs`: Map of input specifications with binding information
- `:outputs`: Output specifications (binding logic not yet implemented)
- `:requirements`/`:hints`: Docker and other execution requirements

Input processing follows this pipeline:
1. Associate provided values with inputs
2. Apply default values where specified
3. Sort inputs by position from inputBinding
4. Format values based on type (string, File, Directory)
5. Build final command line

## Development Environment

The `dev/user.clj` namespace contains:
- Sample CWL tools and job definitions for testing
- Templates for common CWL constructs
- Portal integration for data inspection
- Helper functions for REPL-driven development

Use the dev namespace examples like `hello-world-tool` and `javac-tool` to understand the expected data structures and test functionality interactively.

## CWL Specification Reference

The authoritative CWL v1.2 spec lives at <https://www.commonwl.org/v1.2/>. The
most relevant pages:
- **CommandLineTool**: <https://www.commonwl.org/v1.2/CommandLineTool.html> —
  what Fleur implements today.
- **Workflow**: <https://www.commonwl.org/v1.2/Workflow.html> — Phase 4 target.
- **User guide**: <https://www.commonwl.org/user_guide/> — worked examples.

A copy of the source schema (SALAD `.yml`) and prose is vendored under
`docs/cwl-v1.2/` (`CommandLineTool.yml`, `Workflow.yml`, `Process.yml`,
`Operation.yml`, `concepts.md`, `invocation.md`). These `.yml` files are the
normative field definitions — consult them when unsure about a field's meaning
or defaults.

### Process types
Every CWL document has a `class`. The four process types are `CommandLineTool`,
`ExpressionTool`, `Workflow`, and `Operation`. Fleur handles `CommandLineTool`,
`ExpressionTool`, and static-DAG `Workflow` processes with scatter/gather (all
dispatched via `fleur.process/run`); `Operation` and `Workflow` conditional
`when` are not yet implemented. Input type shorthands (e.g. `n: int`, `int?`,
`File[]`) are expanded by `fleur.preprocess/preprocess`, but `run` does not apply
it automatically yet — preprocess the document first, or write inputs in full
form (`n: {type: int}`).

### Command-line building algorithm (CommandLineTool §4.1)
This is the algorithm `build-command-line` implements:
1. Collect `CommandLineBinding` objects from `arguments`; sort key `[position, i]`
   where `i` is the index in the `arguments` list.
2. Collect bindings from `inputs` (recursively for record/array/map types);
   sort key is `[position, ...]` down to each leaf binding.
3. Ties are broken by the field/parameter name of the leaf binding; **numeric
   entries sort before strings** (so positioned `arguments` precede inputs on a tie).
4. Sort all entries by their sort key.
5. Render each binding to tokens using the rules below.
6. Prepend `baseCommand` (which may be a string or a list) to the front.

### CommandLineBinding fields
- `position` (default `0`): the primary sort key.
- `prefix`: a flag string added before the value.
- `separate` (default `true`): if true, `prefix` and value are separate argv
  entries; if false they are concatenated into one.
- `itemSeparator`: for arrays, join elements into one string with this separator.
- `valueFrom`: a constant or expression that replaces/derives the value.
- `shellQuote` (default `true`): only meaningful under `ShellCommandRequirement`.

### Type → argument rules
- **null**: add nothing.
- **boolean**: if true add `prefix`; if false add nothing.
- **string / number**: add `prefix` and the (decimal) value.
- **File / Directory**: add `prefix` and the value of `.path`.
- **array**: with `itemSeparator`, add `prefix` + joined string; otherwise add
  `prefix` then recurse into each element; an empty array adds nothing.
- **record/object**: add `prefix` only, then recurse into fields that have an
  `inputBinding`. (Not yet implemented in Fleur.)

### Runtime notes
- Values referenced as `$(...)` / `${...}` are **parameter references /
  expressions** (`runtime.*`, `inputs.*`, etc.). `fleur.expression` evaluates
  them: parameter references always, and full JavaScript when the tool declares
  `InlineJavascriptRequirement`. `build-command-line`'s 2-arity form takes an
  `evaluation-context` (see `fleur.command-line-tool/evaluation-context`) and
  resolves `arguments`/`valueFrom`; the 1-arity form still emits them literally.
  `execute` and `bind-outputs` are likewise context-aware, evaluating
  `stdin`/`stdout`/`stderr` and output `glob`/`secondaryFiles`/`format`.
- The `runtime` object is populated by `fleur.runtime/make-runtime` (outdir and
  tmpdir are created as temp dirs unless supplied; cores/ram come from
  `ResourceRequirement` or sensible defaults). `command-line-tool/run` ties the
  whole pipeline together: defaults -> job values -> resolve input paths ->
  runtime -> (optional stage-inputs) -> InitialWorkDirRequirement -> context ->
  build -> execute -> bind-outputs.
- Input Files are resolved to absolute paths (`fleur.staging/resolve-inputs`,
  base dir = cwd or `run`'s `:basedir`), so tools run in `runtime.outdir` still
  find their inputs. `run`'s `:stage-inputs?` copies inputs into the working dir;
  `InitialWorkDirRequirement` entries are always staged there.
- **Done**: `loadContents` on File inputs (top-level or `inputBinding`, first
  64 KiB) and on output `outputBinding` (incl. `outputEval`); `EnvVarRequirement`
  in local (non-Docker) execution.
- **Not yet done**: symlink-vs-copy policy tuning; stdout/stderr are captured
  then written to file (no streaming/binary redirection); `EnvVarRequirement`
  under Docker (`docker run -e`).
- Preprocessing (`$import`/`$include`, `$graph`, identifier and type-name
  resolution) is defined by schema-salad. `fleur.preprocess` provides a
  backend-swappable API: the native `:clojure` backend currently does type-DSL
  expansion + inputs/outputs normalization (and errors on `$import`/`$graph`),
  while `:schema-salad-tool` shells out for full fidelity. Preprocessing is not
  yet applied automatically by `run` — call `fleur.preprocess/preprocess[-file]`
  before running a document that uses these features.

## Suggested Implementation Roadmap

### Phase 1: Core CommandLineTool Fixes
- ✅ Complete `bind-outputs` function with glob pattern matching
- ✅ Command-line building rewritten to follow the CWL algorithm: vector
  `baseCommand` splicing, `arguments`, position sorting, and the
  `prefix`/`separate`/`itemSeparator` CommandLineBinding rules plus per-type
  formatting (string, number, boolean, File, Directory, array, null). The old
  `format-input-value` was replaced by `binding-tokens`.
- ✅ Evaluate CWL parameter references / expressions (`$(runtime.outdir)`,
  `$(inputs.x)`, `${ ... }`) via `fleur.expression` (pure-Clojure parameter
  references + Rhino JavaScript under `InlineJavascriptRequirement`), wired into
  `build-command-line` (`arguments`/`valueFrom`), `execute`
  (`stdin`/`stdout`/`stderr`), and `bind-outputs` (`glob`/`secondaryFiles`/`format`).
- ✅ Populate the `runtime.*` object automatically (`fleur.runtime/make-runtime`).
- ✅ `stdin`/`stdout`/`stderr` redirection (in `execute`).
- ✅ Output file collection incl. `secondaryFiles` (suffix/`^` and expression
  patterns), `format`, `loadContents`, and `outputEval` (scalar/array outputs
  derived from globbed Files). Still to do: output validation.
- ✅ Resolve input File paths to absolute + populate File metadata, stage inputs
  into the working directory, and process `InitialWorkDirRequirement`
  (`fleur.staging`). The `tar_extract` sample now runs end-to-end.
- Add proper error handling throughout execution pipeline

### Phase 2: Docker Integration  
- ✅ Process `DockerRequirement` in `run`: resolve/pull the image, mount inputs
  read-only and the outdir/tmpdir read-write, remap input paths to their
  container locations, run the command via `docker run -w <container outdir>`,
  and collect outputs from the host outdir (`fleur.docker`). Verified end-to-end
  against `busybox`.
- ✅ Run the container as the invoking user (`--user <uid:gid>`, default on; see
  `run`'s `:match-user?`/`:docker-user`) so outputs aren't root-owned.
- ✅ Honor the CWL `NetworkAccess` requirement: `docker run --network none`
  unless `networkAccess` is enabled (`fleur.docker/network-arg`).
- Still to do: `dockerFile`/`dockerLoad`/`dockerImport` image sources, and
  `--gpus`/other resource controls.

### Phase 3: Schema Validation & Testing
- ✅ Set up test framework (`clojure.test` via Cognitect test-runner, `:test` alias)
- ✅ Backend-swappable preprocessing API (`fleur.preprocess`). The default
  `:cwljava` backend (schema-salad-generated Java SDK via JitPack) does
  full-fidelity loading, adapted back into Fleur maps; a native `:clojure`
  subset and a `:schema-salad-tool` shell remain available.
- ✅ Preprocessing wired into execution via `fleur.process/run-file`, which
  preprocesses a CWL file (cwljava by default; resolved workflow refs shortened
  back to Fleur's `stepid/outputid` form) and runs it. The map-based `run`
  stays a plain executor of an already-normalized process. Still to do:
  optionally revisit cwljava sourcing (JitPack vs. build-and-host/vendor) and
  grow the native subset as a dependency-light fallback.
- Add clojure.spec or schema validation for CWL documents
- Add integration tests that execute real CWL files end-to-end
- Validate against CWL conformance tests

### Phase 4: Workflow Support
- ✅ `ExpressionTool` process class (`fleur.expression-tool`) and a class
  dispatcher (`fleur.process/run`) covering CommandLineTool + ExpressionTool.
- ✅ `Workflow` class parsing (steps, `in`/`out`, `outputSource`; map & list
  forms) and step dependency resolution — topological order via an ubergraph
  digraph with cycle detection, output→input wiring, step `default`/`valueFrom`,
  requirement inheritance, sub-workflow recursion, and multi-file `run:`
  references (`fleur.workflow`). Example pipeline:
  `workflows/variant-calling/` (bwa + samtools + bcftools).
- ✅ Scatter/gather (`fleur.workflow`): single-input scatter and multi-input
  `scatterMethod` (`dotproduct`, `flat_crossproduct`, `nested_crossproduct`),
  gathering step outputs into (nested) arrays; an empty scatter input yields
  empty outputs. Not yet: per-element `valueFrom` on a scattered input, and
  `when` evaluated per scatter job.
- Implement conditional step execution (`when`) and multi-source `linkMerge`
  (input type-shorthand normalization now lives in `fleur.preprocess`)
