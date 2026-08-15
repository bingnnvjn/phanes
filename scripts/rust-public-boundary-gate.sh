#!/usr/bin/env bash
#
# Fable Rust publication-boundary gate (工单 56).
#
# The allow-list is data, not duplicated shell logic:
# docs/security/rust-public-boundary.allowlist
#
# This gate is read-only. It checks each working tree and a local fresh clone,
# without creating remotes, pushing refs, or enabling workflows.

set -u

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
ALLOWLIST="$REPO_ROOT/docs/security/rust-public-boundary.allowlist"
CRATES=(fable-boo spike-render spike-session)

failed=0
checks=0
failures=0

pass() {
    checks=$((checks + 1))
    printf 'PASS: %s\n' "$1"
}

fail() {
    checks=$((checks + 1))
    failures=$((failures + 1))
    failed=1
    printf 'FAIL: %s\n' "$1" >&2
}

if [[ ! -f "$ALLOWLIST" ]]; then
    fail "missing canonical allow-list: $ALLOWLIST"
fi

allowed_path() {
    local crate="$1"
    local path="$2"
    local owner pattern

    while IFS=$'\t' read -r owner pattern; do
        [[ -z "$owner" || "${owner:0:1}" == "#" ]] && continue
        [[ -n "$pattern" ]] || continue
        if [[ "$owner" == "$crate" || "$owner" == "*" ]] &&
            [[ "$path" == $pattern ]]; then
            return 0
        fi
    done < "$ALLOWLIST"
    return 1
}

check_tree() {
    local crate="$1"
    local repo="$2"
    local relative tree_failed=0

    while IFS= read -r -d '' relative; do
        if ! allowed_path "$crate" "$relative"; then
            printf '  unexpected path: %s/%s\n' "$crate" "$relative" >&2
            tree_failed=1
        fi
    done < <(git -C "$repo" ls-files -z)

    if [[ "$tree_failed" -eq 0 ]]; then
        pass "$crate Git publish input matches docs/security/rust-public-boundary.allowlist"
    else
        fail "$crate Git publish input contains a path outside the canonical allow-list"
    fi
}

check_forbidden_artifacts() {
    local crate="$1"
    local repo="$2"
    local relative hit=0

    while IFS= read -r -d '' relative; do
        case "$relative" in
            *.jks|*.keystore|*.p12|*.pfx|*.pem|*.key|*.apk|*.aab|*.log|\
            .env|.env.*|local.properties|signing.properties)
                hit=1
                ;;
        esac
    done < <(git -C "$repo" ls-files -z)

    if [[ "$hit" -ne 0 ]]; then
        fail "$crate Git publish input contains a forbidden artifact"
    else
        pass "$crate Git publish input contains no known forbidden artifact"
    fi
}

check_repo() {
    local crate="$1"
    local repo="$2"
    local top expected_top

    expected_top="$(CDPATH= cd -- "$repo" && pwd)"
    top="$(git -C "$repo" rev-parse --show-toplevel 2>/dev/null || true)"
    if [[ "$top" == "$expected_top" ]]; then
        pass "$crate has an independent Git top-level"
    else
        fail "$crate Git top-level is not its crate directory"
        return
    fi

    if [[ -z "$(git -C "$repo" remote)" ]]; then
        pass "$crate has no remote while publication is frozen"
    else
        fail "$crate has a configured remote"
    fi

    if git -C "$repo" diff --check; then
        pass "$crate working tree diff --check"
    else
        fail "$crate working tree diff --check"
    fi

    check_tree "$crate" "$repo"
    check_forbidden_artifacts "$crate" "$repo"

    local tmp_root clone
    tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/fable-public-boundary.XXXXXX")"
    clone="$tmp_root/$crate"
    if git clone --no-local --quiet "$repo" "$clone" &&
        [[ "$(git -C "$clone" rev-parse --show-toplevel)" == "$clone" ]]; then
        pass "$crate fresh clone has an independent Git top-level"
        check_tree "$crate" "$clone"
        check_forbidden_artifacts "$crate" "$clone"
        if git -C "$clone" diff --check; then
            pass "$crate fresh clone diff --check"
        else
            fail "$crate fresh clone diff --check"
        fi
    else
        fail "$crate fresh clone could not be created or has the wrong Git top-level"
    fi
    rm -rf "$tmp_root"
}

for crate in "${CRATES[@]}"; do
    check_repo "$crate" "$REPO_ROOT/$crate"
done

printf '\nRust public-boundary gate: %d checks, %d failures\n' "$checks" "$failures"
if [[ "$failed" -ne 0 ]]; then
    exit 1
fi
