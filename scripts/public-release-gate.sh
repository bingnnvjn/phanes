#!/usr/bin/env bash
#
# Fable public-release gate (工单 55; 工单 63 改为 ADR-0011 的单仓库形态).
#
# This is a read-only, fail-closed audit. It never creates remotes, pushes
# refs, rewrites history, changes GitHub settings, or enables a workflow.
# A successful run is necessary but not sufficient for publication: the
# private-rehearsal, branch-protection, anonymous-access, and human review
# checks still require a Fable-owned GitHub repository.

set -uo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"

CRATES=(boo session renderer)
EXPECTED_GITLEAKS_VERSION="v8.29.0"
GITLEAKS_CONFIG="$REPO_ROOT/docs/release/gitleaks-public-release.toml"
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

Read-only audit of the single merged repository (ADR-0011) and its Rust
role directories. A dedicated secret scanner is required; set
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

required_file() {
    local repo="$1"
    local path="$2"
    if [[ -f "$repo/$path" ]]; then
        pass "$repo/$path exists"
    else
        fail "$repo/$path is required for a public repository"
    fi
}

has_forbidden_tracked_path() {
    local crate="$1"
    local relative

    while IFS= read -r -d '' relative; do
        case "$relative" in
            *.jks|*.keystore|*.p12|*.pfx|*.pem|*.key|*.apk|*.aab|*.log|\
            out.png|coverage_report.txt|.env|.env.*|local.properties|signing.properties)
                return 0
                ;;
        esac
    done < <(git -C "$REPO_ROOT" ls-files -z -- "$crate")
    return 1
}

for crate in "${CRATES[@]}"; do
    repo="$REPO_ROOT/$crate"
    if [[ -d "$repo" ]]; then
        pass "$crate role directory exists"
    else
        fail "$crate role directory is missing"
        continue
    fi

    # 工单 67：crate 级 SECURITY.md / CONTRIBUTING.md 已删除，规则收敛到根级
    # SECURITY.md 与 docs/agents/ 下的质量/供应链文档；LICENSE 与 THIRD_PARTY.md 仍在 crate 内。
    for path in Cargo.toml Cargo.lock README.md LICENSE THIRD_PARTY.md; do
        required_file "$repo" "$path"
    done
    # ADR-0011 单仓库形态：crate 级 workflow 目录不再承载 required checks。
    if [[ -d "$repo/.github/workflows" ]]; then
        note "$crate still carries a crate-level workflow directory; 工单 64 must keep or remove it explicitly"
    fi
    if [[ "$crate" == "renderer" ]]; then
        if grep -q 'still require verification' "$repo/THIRD_PARTY.md" 2>/dev/null; then
            fail "renderer JetBrains Mono provenance/license is unresolved"
        elif [[ ! -f "$repo/assets/JETBRAINS-MONO-LICENSE.txt" ]]; then
            fail "renderer JetBrains Mono license notice is missing"
        else
            pass "renderer JetBrains Mono provenance and license notice are present"
        fi
    fi
    if has_forbidden_tracked_path "$crate"; then
        fail "$crate contains a signing, credential, build, or environment artifact"
    else
        pass "$crate has no known signing or environment artifact in its publish tree"
    fi

    if git -C "$REPO_ROOT" diff --check -- "$crate"; then
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

if [[ -f "$REPO_ROOT/docs/release/third-party-sources.md" ]]; then
    pass "third-party source and license ledger exists"
else
    fail "third-party source and license ledger is missing"
fi
if [[ -f "$GITLEAKS_CONFIG" ]]; then
    pass "gitleaks public-release path policy exists"
else
    fail "gitleaks public-release path policy is missing"
fi

android_dir="$REPO_ROOT/android"
if [[ -d "$android_dir" ]]; then
    pass "android role directory exists"
else
    fail "android role directory is missing"
fi

# 许可与声明（工单 67）：根级分区声明 + android 的上游 NOTICE 与 GPLv3 全文
required_file "$REPO_ROOT" "LICENSE.md"
required_file "$REPO_ROOT" "SECURITY.md"
required_file "$REPO_ROOT" "android/LICENSE.md"
required_file "$REPO_ROOT" "android/NOTICE.md"
required_file "$REPO_ROOT" "android/GPL-3.0.txt"

# 远端：只允许工单 65 建立的私有 Phanes origin；public 切换是独立的已确认步骤。
remotes="$(git -C "$REPO_ROOT" remote)"
if [[ -z "$remotes" ]]; then
    pass "the merged repository has no remote; publication is a separate confirmed step"
elif [[ "$remotes" == "origin" ]]; then
    origin_url="$(git -C "$REPO_ROOT" remote get-url origin)"
    case "$origin_url" in
        https://github.com/bingnnvjn/phanes.git|https://github.com/bingnnvjn/phanes)
            pass "the only remote is the expected Phanes origin (工单 65; 同名重建见 ADR-0016)" ;;
        *)
            fail "origin is not the expected Phanes repository; publication is a separate confirmed step" ;;
    esac
else
    fail "unexpected remote set; publication is a separate confirmed step (ADR-0012 决定 1)"
fi

# android 作为发布输入，不得跟踪任何签名、凭据、构建或环境产物。
if has_forbidden_tracked_path android; then
    fail "android contains a signing, credential, build, or environment artifact"
else
    pass "android has no known signing or credential artifact in its publish tree"
fi

# 索引、全部 ref、reflog 与不可达对象中不得残留任何签名材料路径。
# 只输出结论，不输出路径，避免二次泄露。
historical_signing="$(
    git -C "$REPO_ROOT" log --all --reflog --format= --name-only 2>/dev/null |
        grep -aE '\.(jks|keystore|p12|pfx|pem|key|bks|pk8)$' || true
)"
if [[ -n "$historical_signing" ]]; then
    fail "merged history or reflog retains a signing-material path"
else
    pass "merged history and reflog contain no signing-material path"
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

scan_gitleaks_unreachable_blobs() {
    local scanner="$1"
    local repo="$2"
    local label="$3"
    local object_id scanned=0 failed=0

    while IFS= read -r object_id; do
        [[ -n "$object_id" ]] || continue
        scanned=$((scanned + 1))
        if ! git -C "$repo" cat-file blob "$object_id" >/dev/null 2>&1; then
            failed=1
            continue
        fi
        if ! "$scanner" stdin --no-banner --redact --exit-code 1 \
            --config "$GITLEAKS_CONFIG" \
            < <(git -C "$repo" cat-file blob "$object_id") >/dev/null 2>&1; then
            failed=1
        fi
    done < <(
        git -C "$repo" fsck --full --no-reflogs --unreachable 2>/dev/null |
            awk '$1 == "unreachable" && $2 == "blob" { print $3 }'
    )

    if [[ "$failed" -eq 0 ]]; then
        pass "$label gitleaks scanned $scanned unreachable Git blobs without findings"
        return 0
    else
        fail "$label gitleaks found a secret-like value or could not scan an unreachable Git blob"
        return 1
    fi
}

scan_worktree "$REPO_ROOT" "."
scan_git_objects "$REPO_ROOT" "."

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
            scanner_version="$("$scanner" version 2>/dev/null || true)"
            if [[ "$scanner_version" != "$EXPECTED_GITLEAKS_VERSION" ]]; then
                fail "gitleaks version is not pinned to $EXPECTED_GITLEAKS_VERSION"
                scanner_failed=1
            fi
            if [[ ! -f "$GITLEAKS_CONFIG" ]]; then
                scanner_failed=1
            fi
            if ! "$scanner" dir --no-banner --redact --exit-code 1 \
                --config "$GITLEAKS_CONFIG" \
                "$REPO_ROOT" >/dev/null 2>&1; then
                scanner_failed=1
            fi
            if ! "$scanner" git --no-banner --redact --exit-code 1 \
                --config "$GITLEAKS_CONFIG" \
                --log-opts='--all --reflog' "$REPO_ROOT" >/dev/null 2>&1; then
                scanner_failed=1
            fi
            if ! scan_gitleaks_unreachable_blobs \
                "$scanner" "$REPO_ROOT" "."; then
                scanner_failed=1
            fi
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
    pass "Rust quality gate (fmt/check/clippy/test/rustdoc) passed for boo, session, renderer"
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

# 工单 67：三个只读检查工作流落库到根 .github/workflows（ADR-0011 单仓库形态）。
workflow_dir="$REPO_ROOT/.github/workflows"
if [[ -d "$workflow_dir" ]]; then
    for workflow in rust-quality.yml rust-supply-chain.yml public-release-gate.yml; do
        required_file "$REPO_ROOT" ".github/workflows/$workflow"
    done
else
    fail "root .github/workflows is missing; 工单 67 lands the three read-only checks"
fi

note "CI required checks, branch protection, private rehearsal, anonymous inspection, and leak-response ownership require a Fable-owned GitHub remote and remain human-gated."

printf '\nPublic release gate: %d checks, %d failures\n' "$CHECKS" "$FAILURES"
if [[ "$FAILED" -ne 0 ]]; then
    exit 1
fi
