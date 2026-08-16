"""Threshold calibration against a running instance, using the real provider.

Why this exists alongside ThresholdCalibrationIT: the integration test runs
against the offline stub embedder, because the test suite must pass with no API
key. That validates the *method* but not the number. This script points the same
labelled question set at a live service so the number reflects the embedding
model actually in use.

Usage (service running, corpus loaded):

    python3 samples/calibrate.py                    # tenant "greenwood"
    python3 samples/calibrate.py --tenant demo --base-url http://localhost:8080

It reads `metadata.topSimilarity` from each answer, so it needs no special
endpoint and no access to the database. Questions that are refused cost nothing
at all - no model call is made for them.
"""

import argparse
import json
import urllib.error
import urllib.request

# Questions the sample corpus in ./samples genuinely answers.
ANSWERABLE = [
    "What is the late fee per week for a term fee paid after the due date?",
    "What is the tuition fee for Class 9 in term 2?",
    "What is the sibling concession for the second child?",
    "What is the transport charge per term for a distance of 10 km to 15 km?",
    "What time does the route 4 bus depart in the morning?",
    "How many days of casual leave are staff entitled to per year?",
    "How many weeks of maternity leave are available?",
    "What attendance percentage is required to sit the term examination?",
    "What is the pass mark in each subject?",
    "What is the charge for re-evaluation of a paper per subject?",
    "Is the admission fee refundable?",
    "What happens if fees are unpaid 45 days after the due date?",
]

# Questions the corpus does NOT answer. The last four are near misses: they use
# the corpus's own vocabulary to ask about something it never states, which is
# where a too-low threshold produces a confident wrong answer.
UNANSWERABLE = [
    "Which team won the football world cup in 2022?",
    "What is the capital city of Australia?",
    "How do I reset my school portal password?",
    "What is the school's policy on staff medical insurance premiums?",
    "What is the late fee for the hostel accommodation charge?",
    "What is the tuition fee for the pre-nursery playgroup in term 2?",
    "How many days of study leave are staff entitled to before exams?",
    "What is the transport charge for a distance above 25 km?",
]

CANDIDATES = [0.50, 0.55, 0.60, 0.62, 0.65, 0.70, 0.72, 0.75, 0.78,
              0.80, 0.82, 0.85, 0.88, 0.90]


def top_similarity(base_url: str, tenant: str, question: str) -> float:
    payload = json.dumps({"question": question}).encode()
    request = urllib.request.Request(
        f"{base_url}/api/v1/chat",
        data=payload,
        headers={"Content-Type": "application/json", "X-Tenant-Id": tenant},
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            body = json.load(response)
    except urllib.error.HTTPError as error:
        raise SystemExit(
            f"Request failed ({error.code}). Is the service running and the "
            f"corpus loaded for tenant '{tenant}'?\n{error.read().decode()[:300]}"
        )
    return float(body["metadata"]["topSimilarity"])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--tenant", default="greenwood")
    args = parser.parse_args()

    print(f"Scoring {len(ANSWERABLE)} answerable and {len(UNANSWERABLE)} "
          f"unanswerable questions against {args.base_url} ...\n")

    answerable = [(q, top_similarity(args.base_url, args.tenant, q)) for q in ANSWERABLE]
    unanswerable = [(q, top_similarity(args.base_url, args.tenant, q)) for q in UNANSWERABLE]

    print("ANSWERABLE (want these ABOVE the threshold)")
    for question, score in sorted(answerable, key=lambda x: x[1]):
        print(f"  {score:.4f}  {question[:66]}")
    print("\nUNANSWERABLE (want these BELOW the threshold)")
    for question, score in sorted(unanswerable, key=lambda x: -x[1]):
        print(f"  {score:.4f}  {question[:66]}")

    print(f"\n{'threshold':<11}{'answerable kept':<18}{'unanswerable leaked':<22}verdict")
    print("-" * 78)

    best = None
    for threshold in CANDIDATES:
        kept = sum(1 for _, s in answerable if s >= threshold)
        leaked = sum(1 for _, s in unanswerable if s >= threshold)
        if leaked:
            verdict = "answers questions it should refuse"
        elif kept < len(answerable):
            verdict = f"refuses {len(answerable) - kept} answerable"
        else:
            verdict = "full recall, no leakage"
            best = threshold if best is None else max(best, threshold)
        print(f"{threshold:<11.2f}{f'{kept} / {len(answerable)}':<18}"
              f"{f'{leaked} / {len(unanswerable)}':<22}{verdict}")

    print("-" * 78)
    lowest_clean = next((t for t in CANDIDATES
                         if not any(s >= t for _, s in unanswerable)), None)
    worst_answerable = min(s for _, s in answerable)
    best_unanswerable = max(s for _, s in unanswerable)

    print(f"lowest answerable score      : {worst_answerable:.4f}")
    print(f"highest unanswerable score   : {best_unanswerable:.4f}")
    print(f"separation                   : {worst_answerable - best_unanswerable:+.4f}")
    print(f"lowest threshold with no leak: "
          f"{f'{lowest_clean:.2f}' if lowest_clean else 'none in range'}")

    if worst_answerable > best_unanswerable:
        midpoint = (worst_answerable + best_unanswerable) / 2
        print(f"\nRECOMMENDED rag.retrieval.similarity-threshold = {midpoint:.2f}")
        print("(midpoint of the gap: refuses everything out of scope, keeps everything in)")
    else:
        print("\nThe two sets OVERLAP - no threshold separates them cleanly.")
        print("Pick the value that refuses all out-of-scope questions and accept the")
        print("recall loss, because a wrong fee figure is worse than a refusal. The")
        print("model-sentinel gate is the backstop for what gets through.")


if __name__ == "__main__":
    main()
