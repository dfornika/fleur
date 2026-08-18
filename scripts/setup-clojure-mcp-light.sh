#!/usr/bin/env bash
#
# Install clojure-mcp-light (https://github.com/bhauman/clojure-mcp-light) and
# its toolchain (Babashka + bbin) into this environment.
#
# This is written for the ephemeral Claude Code web/remote container, where the
# tools are NOT preinstalled and outbound HTTPS goes through a TLS-terminating
# proxy. It is idempotent: re-running it skips anything already present.
#
# Usage:
#   bash scripts/setup-clojure-mcp-light.sh
#
# After running, add ~/.local/bin to your PATH (the script does this for the
# current shell only):
#   export PATH="$PATH:$HOME/.local/bin"
#
# See docs/dev-setup/clojure-mcp-light.md for the full story, including why the
# truststore workaround below is needed.

set -euo pipefail

MCP_LIGHT_URL="https://github.com/bhauman/clojure-mcp-light.git"
MCP_LIGHT_TAG="v0.2.2"
BBIN_VERSION="v0.2.5"
LOCAL_BIN="$HOME/.local/bin"

export PATH="$PATH:$LOCAL_BIN"
mkdir -p "$LOCAL_BIN"

# ---------------------------------------------------------------------------
# The proxy in the Claude container re-terminates TLS, so Java-based tools must
# trust /root/.ccr/ca-bundle.crt. The JVM `clojure` CLI already picks this up
# via JAVA_TOOL_OPTIONS, but Babashka is a native binary that bootstraps the
# Clojure tools with its OWN downloader, which ignores JAVA_TOOL_OPTIONS and
# fails with "PKIX path building failed". Fix: pass the truststore system
# properties explicitly on the `bb` command line. We reuse whatever trustStore
# is already configured in JAVA_TOOL_OPTIONS so this stays correct even if the
# path changes between sessions.
# ---------------------------------------------------------------------------
TS_OPTS=()
for tok in ${JAVA_TOOL_OPTIONS:-}; do
  case "$tok" in
    -Djavax.net.ssl.trustStore*) TS_OPTS+=("$tok") ;;
  esac
done
if [ ${#TS_OPTS[@]} -eq 0 ]; then
  # Fallback to the documented default location.
  TS_OPTS=(-Djavax.net.ssl.trustStore=/root/.ccr/java-truststore.p12
           -Djavax.net.ssl.trustStorePassword=changeit
           -Djavax.net.ssl.trustStoreType=PKCS12)
fi

# --- 0. Clojure CLI -----------------------------------------------------------
# The JVM `clojure`/`clj` CLI is what actually runs the tests, the REPL, and the
# uberjar build. It is NOT always preinstalled in the ephemeral web/remote
# container (a JDK is, but the Clojure CLI may be missing), so install it if
# absent. It picks up the proxy truststore from JAVA_TOOL_OPTIONS on its own.
if ! command -v clojure >/dev/null 2>&1; then
  echo ">> Installing the Clojure CLI via the official installer"
  _cljinst="$(mktemp)"
  trap 'rm -f "$_cljinst"' EXIT
  curl -fsSL -o "$_cljinst" \
    https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
  # The installer defaults to /usr/local; fall back gracefully if not writable.
  if [ -w /usr/local ] || [ "$(id -u)" = "0" ]; then
    bash "$_cljinst"
  else
    bash "$_cljinst" --prefix "$HOME/.local"
  fi
  if ! command -v clojure >/dev/null 2>&1; then
    echo "!! Clojure CLI installation completed but 'clojure' is still not on PATH" >&2
    exit 1
  fi
  rm -f "$_cljinst"
  trap - EXIT
else
  echo ">> Clojure CLI already installed: $(clojure --version 2>/dev/null || echo present)"
fi

# --- 1. Babashka --------------------------------------------------------------
if ! command -v bb >/dev/null 2>&1; then
  echo ">> Installing Babashka into $LOCAL_BIN"
  curl -sL https://raw.githubusercontent.com/babashka/babashka/master/install \
    | bash -s -- --dir "$LOCAL_BIN"
else
  echo ">> Babashka already installed: $(bb --version)"
fi

# --- 2. bbin ------------------------------------------------------------------
if [ ! -x "$LOCAL_BIN/bbin" ]; then
  echo ">> Installing bbin ($BBIN_VERSION) into $LOCAL_BIN"
  curl -o- -L "https://raw.githubusercontent.com/babashka/bbin/${BBIN_VERSION}/bbin" \
    > "$LOCAL_BIN/bbin"
  chmod +x "$LOCAL_BIN/bbin"
else
  echo ">> bbin already present"
fi

# Always invoke bbin as `bb <ts-opts> ~/.local/bin/bbin ...` so the truststore
# props reach Babashka's native clojure-tools bootstrap.
bbin() { bb "${TS_OPTS[@]}" "$LOCAL_BIN/bbin" "$@"; }

# --- 3. clojure-mcp-light tools ----------------------------------------------
echo ">> Installing clojure-mcp-light tools ($MCP_LIGHT_TAG)"

# Hook tool for Claude Code -> installs as `clj-paren-repair-claude-hook`
bbin install "$MCP_LIGHT_URL" --tag "$MCP_LIGHT_TAG"

# nREPL eval CLI -> `clj-nrepl-eval`
bbin install "$MCP_LIGHT_URL" --tag "$MCP_LIGHT_TAG" \
  --as clj-nrepl-eval --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'

# On-demand delimiter repair -> `clj-paren-repair`
bbin install "$MCP_LIGHT_URL" --tag "$MCP_LIGHT_TAG" \
  --as clj-paren-repair --main-opts '["-m" "clojure-mcp-light.paren-repair"]'

echo
echo ">> Installed. Available on PATH (add $LOCAL_BIN to PATH if not already):"
bb "${TS_OPTS[@]}" "$LOCAL_BIN/bbin" ls
