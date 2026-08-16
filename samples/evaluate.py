"""End-to-end grounding evaluation against a running instance.

`calibrate.py` measures *retrieval* - whether a chunk clears the similarity
threshold. This measures the thing that actually matters: whether the service
as a whole answers what it should and refuses what it should, with both gates
in play (the similarity threshold, then the model's own insufficient-context
sentinel).

The distinction matters because a similarity threshold provably cannot separate
near-miss questions from real ones - see the README. The second gate is what
catches those, and only an end-to-end measurement shows whether it does.

Usage (service running, corpus loaded):

    python3 samples/evaluate.py
    python3 samples/evaluate.py --tenant demo --base-url http://localhost:8080
"""

import argparse
import json
import urllib.error
import urllib.request

from calibrate import ANSWERABLE, UNANSWERABLE


def ask(base_url: str, tenant: str, question: str) -> dict:
    payload = json.dumps({"question": question}).encode()
    request = urllib.request.Request(
        f"{base_url}/api/v1/chat",
        data=payload,
        headers={"Content-Type": "application/json", "X-Tenant-Id": tenant},
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise SystemExit(
            f"Request failed ({error.code}). Is the service running and the "
            f"corpus loaded for tenant '{tenant}'?\n{error.read().decode()[:300]}"
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--tenant", default="greenwood")
    args = parser.parse_args()

    threshold = None
    rows = []

    for question in ANSWERABLE:
        body = ask(args.base_url, args.tenant, question)
        threshold = body["metadata"]["threshold"]
        rows.append(("answer", question, body))
    for question in UNANSWERABLE:
        body = ask(args.base_url, args.tenant, question)
        rows.append(("refuse", question, body))

    print(f"\nEnd-to-end grounding evaluation  (threshold {threshold})")
    print("=" * 82)

    correct = 0
    for expected, question, body in rows:
        actual = "refuse" if body["refused"] else "answer"
        ok = actual == expected
        correct += ok
        # Which gate fired: no model latency means the threshold gate short
        # -circuited; latency present on a refusal means the model sentinel did.
        if body["refused"]:
            gate = "threshold" if body["metadata"].get("modelMs") is None else "sentinel"
        else:
            gate = "-"
        print(f"  {'PASS' if ok else 'FAIL'}  want={expected:<7} got={actual:<7} "
              f"sim={body['metadata']['topSimilarity']:.4f}  gate={gate:<9} "
              f"{question[:44]}")

    total = len(rows)
    print("=" * 82)
    print(f"  {correct}/{total} correct ({100 * correct / total:.0f}%)")

    by_gate = {}
    for _, _, body in rows:
        if body["refused"]:
            g = "threshold" if body["metadata"].get("modelMs") is None else "sentinel"
            by_gate[g] = by_gate.get(g, 0) + 1
    if by_gate:
        parts = ", ".join(f"{v} by the {k} gate" for k, v in sorted(by_gate.items()))
        print(f"  refusals: {parts}")


if __name__ == "__main__":
    main()
