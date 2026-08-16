#!/usr/bin/env bash
#
# Loads the sample corpus and waits for ingestion to finish.
#
#   ./samples/load.sh                        # tenant "greenwood", localhost:8080
#   ./samples/load.sh springfield             # a different tenant
#   BASE_URL=http://host:8080 ./samples/load.sh
#
# Loading the same corpus under two tenants is the quickest way to demonstrate
# isolation: ask both, and note that neither can see the other's figures.

set -euo pipefail

TENANT="${1:-greenwood}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Writes progress to stderr and the document id to stdout, so that
# `id=$(upload ...)` captures the id and nothing else.
upload() {
    local file="$1" category="$2" title="$3"
    printf '  %-24s %-12s ' "$(basename "$file")" "$category" >&2

    local response
    response=$(curl -sS -X POST "$BASE_URL/api/v1/documents" \
        -H "X-Tenant-Id: $TENANT" \
        -F "file=@$file" \
        -F "category=$category" \
        -F "title=$title")

    local id
    id=$(printf '%s' "$response" | sed -n 's/.*"documentId":"\([^"]*\)".*/\1/p')
    if [ -z "$id" ]; then
        echo "REJECTED" >&2
        printf '    %s\n' "$response" >&2
        return 1
    fi
    echo "queued" >&2
    echo "$id"
}

echo "Loading sample corpus into $BASE_URL as tenant '$TENANT'"
echo

ids=()
ids+=("$(upload "$DIR/fee-policy.md"      FEES      'Fee Policy 2026-27')")
ids+=("$(upload "$DIR/transport-rules.md" TRANSPORT 'Transport Rules and Route Timings')")
ids+=("$(upload "$DIR/hr-leave-policy.md" HR        'Staff Leave Policy')")
ids+=("$(upload "$DIR/exam-circular.md"   EXAM      'Term 2 Examination Circular')")

echo
printf 'Waiting for ingestion'
for _ in $(seq 1 60); do
    pending=0
    for id in "${ids[@]}"; do
        status=$(curl -sS "$BASE_URL/api/v1/documents/$id" -H "X-Tenant-Id: $TENANT" \
            | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')
        [ "$status" = "PROCESSING" ] && pending=$((pending + 1))
    done
    [ "$pending" -eq 0 ] && break
    printf '.'
    sleep 1
done
echo

echo
curl -sS "$BASE_URL/api/v1/documents?size=50" -H "X-Tenant-Id: $TENANT" \
    | python3 "$DIR/summarise.py"

cat <<EOF

Try it:

  curl -sS -X POST $BASE_URL/api/v1/chat \\
    -H 'Content-Type: application/json' -H 'X-Tenant-Id: $TENANT' \\
    -d '{"question":"What is the late fee if I pay term 2 three weeks late?"}'

Or open $BASE_URL/ for the demo page.
EOF
