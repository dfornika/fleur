# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Fleur is a Clojure library for running Common Workflow Language (CWL) workflows. It currently provides functionality to parse, execute, and manage CWL CommandLineTools, with support for Docker containers and schema validation via schema-salad. Though most of that functionality is currently incomplete and not thoroughly tested. It will also support running CWL workflows, though that hasn't been implemented yet.

## Architecture

The codebase is organized into three main namespaces:

- `fleur.command-line-tool`: Core functionality for parsing CWL tools, binding inputs/outputs, and executing commands
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

## Suggested Implementation Roadmap

### Phase 1: Core CommandLineTool Fixes
- ✅ Complete `bind-outputs` function with glob pattern matching
- Fix input value formatting (string quoting issue in `format-input-value`)
- Add proper error handling throughout execution pipeline
- Implement output file collection and validation

### Phase 2: Docker Integration  
- Expand `docker.clj` to handle volume mounting for input/output files
- Implement proper working directory management
- Add Docker requirement processing in command execution

### Phase 3: Schema Validation & Testing
- Add clojure.spec or schema validation for CWL documents
- Set up test framework (probably `clojure.test`)
- Add integration tests with real CWL files
- Validate against CWL conformance tests

### Phase 4: Workflow Support
- Implement `Workflow` class parsing (currently only supports `CommandLineTool`)
- Add step dependency resolution
- Implement scatter/gather operations
