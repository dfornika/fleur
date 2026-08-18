# Installing clojure-mcp-light

[clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) provides three
CLI tools that help AI coding assistants work with Clojure **without an MCP
server**:

| Binary                         | Purpose                                                        |
| ------------------------------ | ------------------------------------------------------------- |
| `clj-nrepl-eval`               | Evaluate Clojure over a running nREPL from the CLI             |
| `clj-paren-repair`             | On-demand delimiter (paren/bracket/brace) repair on files     |
| `clj-paren-repair-claude-hook` | Claude Code hook that auto-repairs delimiters on Write/Edit    |

It targets the "paren edit death loop" (AI edits producing unbalanced
delimiters) and gives REPL access without a long-running MCP process.

## TL;DR — reinstall on a fresh session

The Claude web/remote container is ephemeral, so these tools must be
reinstalled each session. Just run the checked-in script:

```bash
bash scripts/setup-clojure-mcp-light.sh
export PATH="$PATH:$HOME/.local/bin"   # for the current shell
```

The rest of this document explains what that script does and why.

## Prerequisites

- A JDK (preinstalled) + the `clojure`/`clj` CLI. The CLI is **not** always
  present in the container, so the setup script installs it (system-wide via the
  official installer) when missing.
- **Babashka** (`bb`) — fast Clojure scripting runtime; bundles `cljfmt`.
- **bbin** — Babashka's package manager (installs scripts as CLI binaries).
- Optional: `parinfer-rust` for faster repairs (not installed here; the tools
  fall back to `cljfmt`, which ships with Babashka).

None of the Clojure CLI, `bb`, or `bbin` is reliably preinstalled in the
container; the setup script installs whatever is missing.

## The one real gotcha: TLS through the proxy

Outbound HTTPS in the container goes through a TLS-terminating proxy, so every
tool must trust `/root/.ccr/ca-bundle.crt`. The JVM `clojure` CLI already does
(via `JAVA_TOOL_OPTIONS`, which sets `-Djavax.net.ssl.trustStore=...`).

**Babashka does not.** It is a native (GraalVM) binary and, on first use,
bootstraps the Clojure tools with its *own* downloader that ignores
`JAVA_TOOL_OPTIONS`. The first `bbin install` therefore fails with:

```
:borkdude.deps/direct-download (certificate_unknown) PKIX path building failed:
  ... unable to find valid certification path to requested target
```

**Fix:** pass the truststore system properties explicitly on the `bb` command
line when invoking bbin:

```bash
bb -Djavax.net.ssl.trustStore=/root/.ccr/java-truststore.p12 \
   -Djavax.net.ssl.trustStorePassword=changeit \
   -Djavax.net.ssl.trustStoreType=PKCS12 \
   ~/.local/bin/bbin install ...
```

The setup script extracts these `-Djavax.net.ssl.trustStore*` tokens from
`JAVA_TOOL_OPTIONS` automatically, so it stays correct if the path changes.
(Once bbin is bootstrapped, its subprocess calls to the JVM `clojure` CLI pick
up the truststore from `JAVA_TOOL_OPTIONS` on their own.) After install the
tools run offline, so this workaround is only needed at install time.

## Manual steps (what the script automates)

```bash
export PATH="$PATH:$HOME/.local/bin"
mkdir -p "$HOME/.local/bin"

# 0. Clojure CLI (skip if `clojure` is already on PATH)
curl -sL -o /tmp/clj-install.sh \
  https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
bash /tmp/clj-install.sh          # installs into /usr/local (needs root/writable)

# 1. Babashka
curl -sL https://raw.githubusercontent.com/babashka/babashka/master/install \
  | bash -s -- --dir "$HOME/.local/bin"

# 2. bbin (manual install, no Homebrew)
curl -o- -L https://raw.githubusercontent.com/babashka/bbin/v0.2.5/bbin \
  > "$HOME/.local/bin/bbin" && chmod +x "$HOME/.local/bin/bbin"

# 3. clojure-mcp-light tools (note the truststore props on the bb invocation)
TS="-Djavax.net.ssl.trustStore=/root/.ccr/java-truststore.p12 \
-Djavax.net.ssl.trustStorePassword=changeit \
-Djavax.net.ssl.trustStoreType=PKCS12"

bb $TS ~/.local/bin/bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2
bb $TS ~/.local/bin/bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2 \
  --as clj-nrepl-eval  --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'
bb $TS ~/.local/bin/bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2 \
  --as clj-paren-repair --main-opts '["-m" "clojure-mcp-light.paren-repair"]'
```

The plain first install (no `--as`) produces `clj-paren-repair-claude-hook`.

## Verifying the install

```bash
export PATH="$PATH:$HOME/.local/bin"

# List installed bbin packages
bb ~/.local/bin/bbin ls

# Delimiter repair on a file (adds the missing close paren)
printf '(defn add [a b]\n  (+ a b)\n' > /tmp/broken.clj
clj-paren-repair /tmp/broken.clj && cat /tmp/broken.clj   # -> (+ a b))

# nREPL eval (needs a running nREPL; this repo exposes one via `clj -M:nrepl`
# on port 7888, which also writes .nrepl-port)
clj-nrepl-eval -p 7888 "(+ 1 2 3)"     # => 6
clj-nrepl-eval --discover-ports        # finds servers via .nrepl-port files
```

`clj-nrepl-eval` even tolerates unbalanced input (`"(+ 1 2 3"` still returns
`6`), which is the whole point of the tool.

## Using it with this repo's REPL

Start the project nREPL, then eval against it:

```bash
clj -M:dev:nrepl          # starts nREPL on 7888, writes ./.nrepl-port
clj-nrepl-eval -p 7888 "(require 'fleur.command-line-tool) :ok"
```

## Optional: enable the Claude Code auto-repair hook

To have delimiters auto-repaired on every Write/Edit, add this to
`~/.claude/settings.json` (per the upstream README). It is **not** enabled by
the setup script, since it changes harness behavior for the whole session:

```json
{
  "hooks": {
    "PreToolUse":  [{ "matcher": "Write|Edit", "hooks": [{ "type": "command", "command": "clj-paren-repair-claude-hook --cljfmt" }] }],
    "PostToolUse": [{ "matcher": "Edit|Write", "hooks": [{ "type": "command", "command": "clj-paren-repair-claude-hook --cljfmt" }] }],
    "SessionEnd":  [{ "hooks": [{ "type": "command", "command": "clj-paren-repair-claude-hook --cljfmt" }] }]
  }
}
```

`clj-paren-repair-claude-hook` must be on `PATH` (i.e. `~/.local/bin`) for the
hook to resolve.

## Versions installed (2026-08-08)

- Babashka `v1.13.219`
- bbin `v0.2.5`
- clojure-mcp-light `v0.2.2` (commit `d341c23`)
