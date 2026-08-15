#!/usr/bin/env bash
#
# Fable public-release gate (工单 55).
#
# This is a read-only, fail-closed audit. It never creates remotes, pushes
# refs, rewrites history, changes GitHub settings, or enables a workflow.
# A successful run is necessary but not sufficient for publication: the
# private-rehearsal, branch-protection, anonymous-access, and human review
# checks still require a Fable-owned GitHub repository.

set -uo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"

CRATES=(fable-boo spike-render spike-session)
FAILED=0
CHECKS=0
FAILURES=0

pass() {
    CHECKS=$((CHECKS + 1))
    printf 'PASS: %s\n' "$1"
}

fail() {
    CHECKS=$((CHECKS + 1))
    FAILURES=$((FAILURES + 1))
    FAILED=1
    printf 'FAIL: %s\n' "$1" >&2
}

note() {
    printf 'NOTE: %s\n' "$1"
}

usage() {
    cat <<'EOF'
Usage: scripts/public-release-gate.sh

Read-only audit of the three proposed public Rust repositories and the
fable-app signing boundary. A dedicated secret scanner is required; set
SECRET_SCANNER_BIN to a gitleaks-compatible executable before publication.
EOF
}

if [[ "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi
if [[ $# -ne 0 ]]; then
    usage >&2
    exit 2
fi

git_repo() {
    local expected="$1"
    local actual
    actual="$(git -C "$expected" rev-parse --show-toplevel 2>/dev/null || true)"
    [[ "$actual" == "$expected" ]]
}

required_file() {
    local repo="$1"
    local path="$2"
    if [[ -f "$repo/$path" ]]; then
        pass "$repo/$path exists"
    else
        fail "$repo/$path is required for a public repository"
    fi
}

allowed_public_path() {
    local crate="$1"
    local path="$2"
    case "$path" in
        Cargo.toml|Cargo.lock|README.md|LICENSE|SECURITY.md|CONTRIBUTING.md|.gitignore|.github/CODEOWNERS|.github/dependabot.yml)
            return 0
            ;;
    esac
    case "$crate" in
        fable-boo)
            [[ "$path" == src/* || "$path" == data/frames/* || "$path" == build.rs ]]
            ;;
        spike-render)
            [[ "$path" == src/* ||
                "$path" == examples/* ||
                "$path" == docs/* ||
                "$path" == build.rs ||
                "$path" == THIRD_PARTY.md ||
                "$path" == assets/JetBrainsMono-Regular.ttf ||
                "$path" == assets/NotoColorEmoji.ttf ||
                "$path" == assets/NOTO-EMOJI-LICENSE.txt ||
                "$path" == third_party/freetype/* ]]
            ;;
        spike-session)
            [[ "$path" == src/* ||
                "$path" == tests/* ||
                "$path" == build.rs ||
                "$path" == THIRD_PARTY.md ||
                "$path" == vendor/portable-pty/* ]]
            ;;
        *)
            return 1
            ;;
    esac
}

check_public_tree() {
    local crate="$1"
    local repo="$REPO_ROOT/$crate"
    local absolute relative
    local tree_failed=0

    while IFS= read -r -d '' absolute; do
        relative="${absolute#"$repo"/}"
        if ! allowed_public_path "$crate" "$relative"; then
            printf '  unexpected path: %s/%s\n' "$crate" "$relative" >&2
            tree_failed=1
        fi
    done < <(
        find "$repo" \
            -type d \( -name .git -o -name target -o -name build -o \
                -name .gradle -o -name .cxx -o -name .externalNativeBuild \) -prune -o \
            -type f -print0
    )

    if [[ "$tree_failed" -eq 0 ]]; then
        pass "$crate working tree matches the reviewed public path allow-list"
    else
        fail "$crate has files outside the reviewed public path allow-list"
    fi
}

for crate in "${CRATES[@]}"; do
    repo="$REPO_ROOT/$crate"
    if git_repo "$repo"; then
        pass "$crate is an independent Git repository"
    else
        fail "$crate does not have an independent Git repository root"
        continue
    fi

    if [[ -z "$(git -C "$repo" remote)" ]]; then
        pass "$crate has no remote configured while publication is frozen"
    else
        fail "$crate has a configured remote; remove it or document a Fable-owned target before publication"
    fi

    for path in Cargo.toml Cargo.lock README.md LICENSE SECURITY.md CONTRIBUTING.md \
        .github/CODEOWNERS .github/dependabot.yml; do
        required_file "$repo" "$path"
    done
    if [[ "$crate" == "spike-render" || "$crate" == "spike-session" ]]; then
        required_file "$repo" THIRD_PARTY.md
    fi
    workflow_files=""
    if [[ -d "$repo/.github/workflows" ]]; then
        workflow_files="$(
            find "$repo/.github/workflows" -maxdepth 1 -type f \
                \( -name '*.yml' -o -name '*.yaml' \) -print 2>/dev/null
        )"
    fi
    if [[ -n "$workflow_files" ]]; then
        pass "$crate has a CI workflow candidate for remote review"
    else
        fail "$crate has no CI workflow; add it only after confirming a Fable-owned remote (工单 51)"
    fi
    if [[ "$crate" == "spike-render" ]] &&
        grep -q 'still require verification' "$repo/THIRD_PARTY.md" 2>/dev/null; then
        fail "spike-render JetBrains Mono provenance/license is unresolved"
    elif [[ "$crate" == "spike-render" ]]; then
        pass "spike-render font provenance has no unresolved blocker marker"
    fi
    check_public_tree "$crate"

    if find "$repo" -path "$repo/.git" -prune -o -path "$repo/target" -prune -o \
        -type f \( -iname '*.jks' -o -iname '*.keystore' -o -iname '*.p12' \
        -o -iname '*.pfx' -o -iname '*.pem' -o -iname '*.key' -o -iname '*.apk' \
        -o -iname '*.aab' -o -iname '*.log' -o -name 'out.png' -o -name 'coverage_report.txt' \
        -o -name '.env' -o -name '.env.*' \
        -o -name 'local.properties' -o -name 'signing.properties' \) -print -quit |
        grep -q .; then
        fail "$crate contains a signing, credential, build, or environment artifact"
    else
        pass "$crate has no known signing or environment artifact in its publish tree"
    fi

    if git -C "$repo" diff --check; then
        pass "$crate diff --check"
    else
        fail "$crate diff --check"
    fi
done

root_git="$(git -C "$REPO_ROOT" rev-parse --show-toplevel 2>/dev/null || true)"
if [[ "$root_git" == "$REPO_ROOT" ]]; then
    pass "Fable root repository is readable"
else
    fail "Fable root repository is not readable"
fi

fable_app="$REPO_ROOT/fable-app"
upstream_url="$(git -C "$fable_app" config --get remote.origin.url 2>/dev/null || true)"
if [[ "$upstream_url" == "https://github.com/termux/termux-app.git" ]]; then
    pass "fable-app still points to the protected Termux upstream"
else
    fail "fable-app origin is not the protected Termux upstream"
fi

for sensitive in "$fable_app/keystore/<旧签名材料>" \
    "$fable_app/release-missing-credentials.log"; do
    if [[ -e "$sensitive" ]]; then
        fail "sensitive fable-app material exists locally and is outside publication scope"
    else
        pass "sensitive fable-app material is absent from this workspace"
    fi
done

testkey="$fable_app/app/<上游测试签名材料>"
if [[ ! -f "$testkey" ]]; then
    pass "fable-app <上游测试签名材料> is absent"
else
    # Do not accept a password on argv or print keytool output. Without a
    # verified password, this deliberately remains an unclassified blocker.
    entry_types="$(
        set +e
        keytool -list -v -keystore "$testkey" </dev/null 2>/dev/null |
            awk -F': ' '/^Entry type: / { print $2 }'
        exit 0
    )"
    if grep -q '^PrivateKeyEntry$' <<<"$entry_types"; then
        fail "fable-app <上游测试签名材料> contains a PrivateKeyEntry; key-password usability is unverified, so treat it as potentially usable until rotated/excluded"
    elif [[ -n "$entry_types" ]]; then
        fail "fable-app <上游测试签名材料> is classified as non-private-key material; human purpose review is still required"
    else
        fail "fable-app <上游测试签名材料> could not be classified without its password"
    fi
fi

secret_regex='BEGIN[[:space:]]+(RSA|EC|OPENSSH|DSA|PGP)[[:space:]]+PRIVATE KEY|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|xox[baprs]-[0-9A-Za-z-]{10,}|aws_secret_access_key[[:space:]]*=[[:space:]]*[^[:space:]]{16,}|https?://[^[:space:]/]+:[^[:space:]@]+@'

scan_stream() {
    local label="$1"
    if grep -aEq "$secret_regex"; then
        printf '  secret-like match in %s\n' "$label" >&2
        return 1
    fi
    return 0
}

scan_git_objects() {
    local repo="$1"
    local label="$2"
    local status
    git -C "$repo" cat-file --batch < <(
        {
            git -C "$repo" ls-files -s 2>/dev/null | awk '{print $2}'
            git -C "$repo" rev-list --objects --all --reflog 2>/dev/null
            git -C "$repo" fsck --full --no-reflogs --unreachable 2>/dev/null |
                awk '$1 == "unreachable" && $2 == "blob" { print $3 }'
        } | awk '{print $1}' | LC_ALL=C sort -u
    ) | grep -aE "$secret_regex" >/dev/null
    status=$?
    case "$status" in
        1) pass "$label reachable, reflog, and unreachable Git blobs contain no known secret marker" ;;
        0) fail "$label Git history/object scan found a secret-like marker" ;;
        *) fail "$label Git object scan failed before completion" ;;
    esac
}

scan_worktree() {
    local repo="$1"
    local label="$2"
    local absolute hit=0
    while IFS= read -r -d '' absolute; do
        if ! scan_stream "$absolute" <"$absolute"; then
            hit=1
        fi
    done < <(
        find "$repo" \
            -type d \( -name .git -o -name target -o -name build -o \
                -name .gradle -o -name .cxx -o -name .externalNativeBuild \) -prune -o \
            -type f -print0
    )
    if [[ "$hit" -eq 0 ]]; then
        pass "$label working tree contains no known secret marker"
    else
        fail "$label working-tree scan found a secret-like marker"
    fi
}

for repo_label in . fable-boo spike-render spike-session fable-app; do
    repo="$REPO_ROOT/$repo_label"
    scan_worktree "$repo" "$repo_label"
    scan_git_objects "$repo" "$repo_label"
done

scanner="${SECRET_SCANNER_BIN:-}"
if [[ -z "$scanner" ]] && command -v gitleaks >/dev/null 2>&1; then
    scanner="$(command -v gitleaks)"
fi
if [[ -z "$scanner" ]]; then
    fail "dedicated secret scanner unavailable; install gitleaks and set SECRET_SCANNER_BIN before publication"
else
    scanner_name="$(basename "$scanner")"
    case "$scanner_name" in
        gitleaks)
            scanner_failed=0
            for repo_label in . fable-boo spike-render spike-session fable-app; do
                if ! "$scanner" dir --no-banner --redact --exit-code 1 \
                    "$REPO_ROOT/$repo_label" >/dev/null 2>&1; then
                    scanner_failed=1
                fi
                if ! "$scanner" git --no-banner --redact --exit-code 1 \
                    --log-opts='--all --reflog' "$REPO_ROOT/$repo_label" >/dev/null 2>&1; then
                    scanner_failed=1
                fi
            done
            if [[ "$scanner_failed" -eq 0 ]]; then
                pass "gitleaks scanned all publication inputs without findings"
            else
                fail "gitleaks reported a finding or failed to scan a publication input"
            fi
            ;;
        *)
            fail "unsupported dedicated scanner '$scanner_name'; use a gitleaks-compatible executable"
            ;;
    esac
fi

if "$REPO_ROOT/scripts/rust-quality-gate.sh" >/dev/null 2>&1; then
    pass "Rust quality gate (fmt/check/clippy/test/rustdoc) passed for all three crates"
else
    fail "Rust quality gate failed for at least one crate"
fi

cargo_deny_bin="${CARGO_DENY:-}"
if [[ -z "$cargo_deny_bin" ]]; then
    cargo_deny_bin="$(command -v cargo-deny 2>/dev/null || true)"
fi
if [[ -n "$cargo_deny_bin" ]]; then
    if CARGO_DENY="$cargo_deny_bin" \
        "$REPO_ROOT/scripts/rust-supply-chain-gate.sh" >/dev/null 2>&1; then
        pass "Rust supply-chain gate (cargo-deny, locked graphs, and ledger) passed"
    else
        fail "Rust supply-chain gate failed"
    fi
else
    fail "cargo-deny is not installed; supply-chain rehearsal cannot be complete"
fi

if [[ -d "$REPO_ROOT/.github/workflows" ]]; then
    fail "root workflow directory exists; public Rust workflows belong only in confirmed Fable-owned crate remotes"
else
    pass "no root workflow can accidentally publish from the private engineering repository"
fi

note "CI required checks, branch protection, private rehearsal, anonymous inspection, and leak-response ownership require a Fable-owned GitHub remote and remain human-gated."

printf '\nPublic release gate: %d checks, %d failures\n' "$CHECKS" "$FAILURES"
if [[ "$FAILED" -ne 0 ]]; then
    exit 1
fi
