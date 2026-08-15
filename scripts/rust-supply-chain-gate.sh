#!/usr/bin/env bash
#
# Fable Rust supply-chain gate (工单 52).
#
# The three crates are independent manifests. This script checks each one with
# its own lockfile and never permits Cargo to re-resolve dependencies.

set -u

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"

CRATES=(
    "fable-boo"
    "spike-session"
    "spike-render"
)

EXPECTED_CARGO_DENY_VERSION="0.20.2"
CONFIG_FILE="$REPO_ROOT/deny.toml"
LEDGER_FILE="$REPO_ROOT/supply-chain-exceptions.toml"
CARGO_DENY_BIN="${CARGO_DENY:-cargo-deny}"

failed=0
step_count=0
failed_steps=0

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    failed=1
    failed_steps=$((failed_steps + 1))
}

run_step() {
    local label="$1"
    shift
    step_count=$((step_count + 1))
    printf '\n[%02d] %s\n' "$step_count" "$label"
    if "$@"; then
        printf 'PASS: %s\n' "$label"
    else
        local status=$?
        printf 'FAIL(%d): %s\n' "$status" "$label" >&2
        failed=1
        failed_steps=$((failed_steps + 1))
    fi
}

toml_table_field() {
    local table="$1"
    local record="$2"
    local key="$3"
    awk -F'"' -v wanted_table="$table" -v wanted_record="$record" -v wanted_key="$key" '
        $0 == "[[" wanted_table "]]" { record += 1; next }
        record == wanted_record && $1 == wanted_key " = " { print $2; exit }
    ' "$LEDGER_FILE"
}

source_ledger_field() {
    local record="$1"
    local key="$2"
    toml_table_field "source-exceptions" "$record" "$key"
}

path_tree_sha256() {
    local path="$1"
    (
        cd "$REPO_ROOT"
        find "$path" -type f -not -path '*/.git/*' -print0 |
            LC_ALL=C sort -z |
            xargs -0 sha256sum |
            sha256sum |
            awk '{print $1}'
    )
}

direct_source_refs() {
    local manifest="$1"
    awk -v manifest="$manifest" '
        /^[[:space:]]*#/ { next }
        /^\[/ {
            section = $0
            in_dependency_section = section ~ /dependencies/ || section ~ /^\[patch\./
            next
        }
        !in_dependency_section { next }
        /path[[:space:]]*=/ {
            if (match($0, /^[[:space:]]*([^[:space:]=]+)[[:space:]]*=/, package) &&
                match($0, /path[[:space:]]*=[[:space:]]*"([^"]+)"/, source)) {
                print manifest "\t" package[1] "\tpath\t" source[1]
            }
        }
        /git[[:space:]]*=/ {
            if (match($0, /^[[:space:]]*([^[:space:]=]+)[[:space:]]*=/, package) &&
                match($0, /git[[:space:]]*=[[:space:]]*"([^"]+)"/, source)) {
                print manifest "\t" package[1] "\tgit\t" source[1]
            }
        }
    ' "$REPO_ROOT/$manifest"
}

expected_source_refs() {
    local record=0
    local type manifest crate source
    while :; do
        record=$((record + 1))
        type="$(source_ledger_field "$record" "type")"
        [[ -n "$type" ]] || break
        manifest="$(source_ledger_field "$record" "manifest")"
        crate="$(source_ledger_field "$record" "crate")"
        source="$(source_ledger_field "$record" "source")"
        printf '%s\t%s\t%s\t%s\n' "$manifest" "$crate" "$type" "$source"
    done
}

validate_source_ledger() {
    local record=0
    local type manifest crate version source resolved_path upstream upstream_revision
    local license owner reviewed_on review_by reason content_sha256 actual_hash
    local package_name package_version vendor_revision

    if [[ ! -f "$LEDGER_FILE" ]]; then
        fail "missing source ledger: $LEDGER_FILE"
        return
    fi

    while :; do
        record=$((record + 1))
        type="$(source_ledger_field "$record" "type")"
        [[ -n "$type" ]] || break
        manifest="$(source_ledger_field "$record" "manifest")"
        crate="$(source_ledger_field "$record" "crate")"
        version="$(source_ledger_field "$record" "version")"
        source="$(source_ledger_field "$record" "source")"
        upstream="$(source_ledger_field "$record" "upstream")"
        owner="$(source_ledger_field "$record" "owner")"
        reviewed_on="$(source_ledger_field "$record" "reviewed-on")"
        review_by="$(source_ledger_field "$record" "review-by")"
        reason="$(source_ledger_field "$record" "reason")"

        if [[ -z "$manifest" || -z "$crate" || -z "$version" || -z "$source" ||
            -z "$upstream" || -z "$owner" || -z "$reviewed_on" || -z "$review_by" ||
            -z "$reason" ]]; then
            fail "ledger record $record is missing required traceability fields"
            continue
        fi
        if [[ "$reviewed_on" > "$review_by" || "$(date -u +%F)" > "$review_by" ]]; then
            fail "ledger record $record has expired or invalid review date ($reviewed_on -> $review_by)"
            continue
        fi
        if [[ ! -f "$REPO_ROOT/$manifest" ]]; then
            fail "ledger record $record names missing manifest: $manifest"
            continue
        fi

        case "$type" in
            path)
                resolved_path="$(source_ledger_field "$record" "resolved-path")"
                upstream_revision="$(source_ledger_field "$record" "upstream-revision")"
                license="$(source_ledger_field "$record" "license")"
                content_sha256="$(source_ledger_field "$record" "content-sha256")"
                if [[ -z "$resolved_path" || -z "$upstream_revision" || -z "$license" ||
                    -z "$content_sha256" ]]; then
                    fail "path ledger record $record is missing path provenance or digest"
                    continue
                fi
                if [[ ! -f "$REPO_ROOT/$resolved_path/Cargo.toml" ]]; then
                    fail "path ledger record $record names missing package directory: $resolved_path"
                    continue
                fi
                package_name="$(sed -n 's/^name = "\([^"]*\)"$/\1/p' "$REPO_ROOT/$resolved_path/Cargo.toml" | head -n 1)"
                package_version="$(sed -n 's/^version = "\([^"]*\)"$/\1/p' "$REPO_ROOT/$resolved_path/Cargo.toml" | head -n 1)"
                if [[ "$package_name" != "$crate" || "$package_version" != "$version" ]]; then
                    fail "path ledger record $record package identity differs from $resolved_path/Cargo.toml"
                    continue
                fi
                vendor_revision="$(sed -n 's/.*"sha1": "\([^"]*\)".*/\1/p' "$REPO_ROOT/$resolved_path/.cargo_vcs_info.json" | head -n 1)"
                if [[ "$vendor_revision" != "$upstream_revision" ]]; then
                    fail "path ledger record $record upstream revision differs from vendored metadata"
                    continue
                fi
                actual_hash="$(path_tree_sha256 "$resolved_path")"
                if [[ "$actual_hash" != "$content_sha256" ]]; then
                    fail "path ledger record $record content digest changed; renew review before merging"
                fi
                ;;
            git)
                upstream_revision="$(source_ledger_field "$record" "upstream-revision")"
                [[ -n "$upstream_revision" ]] || fail "git ledger record $record lacks immutable upstream revision"
                ;;
            *)
                fail "ledger record $record has unsupported source type: $type"
                ;;
        esac
    done

    local actual_refs expected_refs
    actual_refs="$(for crate in "${CRATES[@]}"; do direct_source_refs "$crate/Cargo.toml"; done | LC_ALL=C sort)"
    expected_refs="$(expected_source_refs | LC_ALL=C sort)"
    if [[ "$actual_refs" != "$expected_refs" ]]; then
        printf 'ERROR: direct path/git sources differ from %s\n' "$LEDGER_FILE" >&2
        printf 'Expected:\n%s\nActual:\n%s\n' "$expected_refs" "$actual_refs" >&2
        failed=1
        failed_steps=$((failed_steps + 1))
    fi
}

validate_traceability() {
    local table="$1"
    local record="$2"
    local label="$3"
    local owner reviewed_on review_by reason

    owner="$(toml_table_field "$table" "$record" "owner")"
    reviewed_on="$(toml_table_field "$table" "$record" "reviewed-on")"
    review_by="$(toml_table_field "$table" "$record" "review-by")"
    reason="$(toml_table_field "$table" "$record" "reason")"
    if [[ -z "$owner" || -z "$reviewed_on" || -z "$review_by" || -z "$reason" ]]; then
        fail "$label ledger record $record is missing owner, review dates, or reason"
        return 1
    fi
    if [[ "$reviewed_on" > "$review_by" || "$(date -u +%F)" > "$review_by" ]]; then
        fail "$label ledger record $record has expired or invalid review date ($reviewed_on -> $review_by)"
        return 1
    fi
}

compare_ledger_entries() {
    local label="$1"
    local expected="$2"
    local actual="$3"
    if [[ "$expected" != "$actual" ]]; then
        printf 'ERROR: %s in %s differ from deny.toml\n' "$label" "$LEDGER_FILE" >&2
        printf 'Ledger:\n%s\nConfig:\n%s\n' "$expected" "$actual" >&2
        failed=1
        failed_steps=$((failed_steps + 1))
    fi
}

deny_advisory_ids() {
    awk '
        $0 == "[advisories]" { in_advisories = 1; next }
        in_advisories && /^\[/ { exit }
        in_advisories && /ignore = \[/ { in_ignore = 1; next }
        in_ignore && /^\]/ { exit }
        in_ignore && /id = "/ {
            value = $0
            sub(/.*id = "/, "", value)
            sub(/".*/, "", value)
            print value
        }
    ' "$CONFIG_FILE"
}

deny_duplicate_specs() {
    awk '
        $0 == "[bans]" { in_bans = 1; next }
        in_bans && /^\[/ { exit }
        in_bans && /skip = \[/ { in_skip = 1; next }
        in_skip && /^\]/ { exit }
        in_skip && /crate = "/ {
            value = $0
            sub(/.*crate = "/, "", value)
            sub(/".*/, "", value)
            print value
        }
    ' "$CONFIG_FILE"
}

deny_allowed_licenses() {
    awk '
        $0 == "[licenses]" { in_licenses = 1; next }
        in_licenses && /^\[/ { exit }
        in_licenses && /allow = \[/ { in_allow = 1; next }
        in_allow && /^\]/ { exit }
        in_allow && /"/ {
            value = $0
            sub(/^[^"]*"/, "", value)
            sub(/".*/, "", value)
            print value
        }
    ' "$CONFIG_FILE"
}

validate_advisory_ledger() {
    local record=0
    local id crate version kind manifest
    local expected=""
    while :; do
        record=$((record + 1))
        id="$(toml_table_field "advisory-exceptions" "$record" "id")"
        [[ -n "$id" ]] || break
        crate="$(toml_table_field "advisory-exceptions" "$record" "crate")"
        version="$(toml_table_field "advisory-exceptions" "$record" "version")"
        kind="$(toml_table_field "advisory-exceptions" "$record" "kind")"
        manifest="$(toml_table_field "advisory-exceptions" "$record" "manifest")"
        if [[ -z "$crate" || -z "$version" || -z "$kind" || -z "$manifest" ]]; then
            fail "advisory ledger record $record is missing id, crate, version, kind, or manifest"
            continue
        fi
        [[ -f "$REPO_ROOT/$manifest" ]] || fail "advisory ledger record $record names missing manifest: $manifest"
        validate_traceability "advisory-exceptions" "$record" "advisory" || true
        expected+="${id}"$'\n'
    done
    compare_ledger_entries "advisory exceptions" \
        "$(printf '%s' "$expected" | sed '/^$/d' | LC_ALL=C sort)" \
        "$(deny_advisory_ids | LC_ALL=C sort)"
}

validate_duplicate_ledger() {
    local record=0
    local crate version
    local expected=""
    while :; do
        record=$((record + 1))
        crate="$(toml_table_field "duplicate-version-exceptions" "$record" "crate")"
        [[ -n "$crate" ]] || break
        version="$(toml_table_field "duplicate-version-exceptions" "$record" "version")"
        if [[ -z "$version" ]]; then
            fail "duplicate-version ledger record $record lacks a version"
            continue
        fi
        validate_traceability "duplicate-version-exceptions" "$record" "duplicate-version" || true
        expected+="${crate}@${version}"$'\n'
    done
    compare_ledger_entries "duplicate-version exceptions" \
        "$(printf '%s' "$expected" | sed '/^$/d' | LC_ALL=C sort)" \
        "$(deny_duplicate_specs | LC_ALL=C sort)"
}

validate_license_ledger() {
    local record=0
    local spdx
    local expected=""
    while :; do
        record=$((record + 1))
        spdx="$(toml_table_field "license-allows" "$record" "spdx")"
        [[ -n "$spdx" ]] || break
        validate_traceability "license-allows" "$record" "license" || true
        expected+="${spdx}"$'\n'
    done
    compare_ledger_entries "allowed licenses" \
        "$(printf '%s' "$expected" | sed '/^$/d' | LC_ALL=C sort)" \
        "$(deny_allowed_licenses | LC_ALL=C sort)"
}

validate_policy_ledger() {
    validate_source_ledger
    validate_advisory_ledger
    validate_duplicate_ledger
    validate_license_ledger
}

manifest_has_advisory_exception() {
    local manifest="$1"
    local record=0
    local ledger_manifest
    while :; do
        record=$((record + 1))
        ledger_manifest="$(toml_table_field "advisory-exceptions" "$record" "manifest")"
        [[ -n "$ledger_manifest" ]] || return 1
        if [[ "$manifest" == "$REPO_ROOT/$ledger_manifest" ]]; then
            return 0
        fi
    done
}

locked_metadata() {
    local manifest="$1"
    cargo metadata --manifest-path "$manifest" --locked --all-features --format-version 1 >/dev/null
}

run_cargo_deny() {
    local manifest="$1"
    local advisory_lint=()
    if manifest_has_advisory_exception "$manifest"; then
        advisory_lint=(-D advisory-not-detected)
    fi
    "$CARGO_DENY_BIN" --manifest-path "$manifest" --config "$CONFIG_FILE" \
        --all-features --locked check -A unmatched-skip "${advisory_lint[@]}" \
        advisories bans licenses sources
}

if [[ ! -f "$CONFIG_FILE" ]]; then
    printf 'ERROR: missing cargo-deny config: %s\n' "$CONFIG_FILE" >&2
    exit 2
fi
if ! command -v "$CARGO_DENY_BIN" >/dev/null 2>&1; then
    printf 'ERROR: cargo-deny is unavailable (%s); install cargo-deny %s or set CARGO_DENY\n' \
        "$CARGO_DENY_BIN" "$EXPECTED_CARGO_DENY_VERSION" >&2
    exit 2
fi
cargo_deny_version="$("$CARGO_DENY_BIN" --version 2>/dev/null || true)"
if [[ "$cargo_deny_version" != "cargo-deny $EXPECTED_CARGO_DENY_VERSION" ]]; then
    printf 'ERROR: expected cargo-deny %s, got %s\n' \
        "$EXPECTED_CARGO_DENY_VERSION" "${cargo_deny_version:-<unavailable>}" >&2
    exit 2
fi

printf 'cargo-deny: %s\n' "$cargo_deny_version"
run_step "policy ledgers" validate_policy_ledger

for crate in "${CRATES[@]}"; do
    manifest="$REPO_ROOT/$crate/Cargo.toml"
    lockfile="$REPO_ROOT/$crate/Cargo.lock"
    if [[ ! -f "$manifest" || ! -f "$lockfile" ]]; then
        fail "$crate is missing Cargo.toml or Cargo.lock"
        continue
    fi

    before_hash="$(sha256sum "$lockfile" | awk '{print $1}')"
    run_step "$crate :: locked metadata" \
        locked_metadata "$manifest"
    after_hash="$(sha256sum "$lockfile" | awk '{print $1}')"
    if [[ "$before_hash" != "$after_hash" ]]; then
        fail "$crate Cargo.lock changed during locked metadata"
    fi

    before_hash="$(sha256sum "$lockfile" | awk '{print $1}')"
    run_step "$crate :: cargo-deny" \
        run_cargo_deny "$manifest"
    after_hash="$(sha256sum "$lockfile" | awk '{print $1}')"
    if [[ "$before_hash" != "$after_hash" ]]; then
        fail "$crate Cargo.lock changed during locked cargo-deny"
    fi
done

printf '\nRust supply-chain gate: %d steps, %d failed\n' "$step_count" "$failed_steps"
if [[ "$failed" -ne 0 ]]; then
    exit 1
fi
