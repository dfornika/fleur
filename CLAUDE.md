# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Fleur is a Clojure library for running Common Workflow Language (CWL) workflows. It currently provides functionality to parse, execute, and manage CWL CommandLineTools, with support for Docker containers and schema validation via schema-salad. Though most of that functionality is currently incomplete and not thoroughly tested. It will also support running CWL workflows, though that hasn't been implemented yet.

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
- `fleur.schema-salad`: Integration with schema-salad-tool for CWL preprocessing and validation  
- `fleur.docker`: Docker image management utilities

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

### Building
```bash
# Build using tools.build
clj -T:build
```

### Testing
Tests use `clojure.test` and run via the Cognitect test-runner:
```bash
# Run the whole suite
clojure -X:test

# Run a single namespace
clojure -X:test :nses '[fleur.command-line-tool-test]'
```
Test files live in `test/`. Two suites exist today:
- `fleur.command-line-tool-test`: behaviour we consider correct (should stay green).
- `fleur.command-line-tool-known-issues-test`: characterization tests pinning
  current buggy behaviour. Each documents the `DESIRED:` result in a comment;
  when a bug is fixed, flip the assertion to the desired value.

## Dependencies

- **schema-salad**: External tool required for CWL preprocessing (installed via conda)
- **Docker**: Required for executing tools with Docker requirements
- **Core Clojure libraries**: data.json for JSON parsing, clj-yaml for YAML support
- **Rhino** (`org.mozilla/rhino`): pure-Java ECMAScript engine used by
  `fleur.expression` to evaluate CWL JavaScript expressions on the JVM

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
`ExpressionTool`, `Workflow`, and `Operation`. Fleur currently handles only
`CommandLineTool`.

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
- **Not yet done**: `loadContents`; symlink-vs-copy policy tuning; stdout/stderr
  are captured then written to file (no streaming/binary redirection).
- Preprocessing (`$import`/`$include`, `$graph`, identifier and type-name
  resolution) is normally done by `schema-salad-tool`; Fleur is moving toward a
  pure-Clojure preprocessing path so it can run without that external tool.

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
  patterns) and `format`. Still to do: `loadContents`, output validation.
- ✅ Resolve input File paths to absolute + populate File metadata, stage inputs
  into the working directory, and process `InitialWorkDirRequirement`
  (`fleur.staging`). The `tar_extract` sample now runs end-to-end.
- Add proper error handling throughout execution pipeline

### Phase 2: Docker Integration  
- Expand `docker.clj` to handle volume mounting for input/output files
- Implement proper working directory management
- Add Docker requirement processing in command execution

### Phase 3: Schema Validation & Testing
- ✅ Set up test framework (`clojure.test` via Cognitect test-runner, `:test` alias)
- Add clojure.spec or schema validation for CWL documents
- Add integration tests that execute real CWL files end-to-end
- Validate against CWL conformance tests

### Phase 4: Workflow Support
- Implement `Workflow` class parsing (currently only supports `CommandLineTool`)
- Add step dependency resolution
- Implement scatter/gather operations
