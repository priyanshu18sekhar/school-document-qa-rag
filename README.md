# Document Q&A Assistant (RAG)

A backend service that ingests a school's policy documents and answers natural-language
questions about them, **with citations back to the source document and page**, and refuses
when it has no grounding.

Java 25 · Spring Boot 4.1 · Spring AI 2.0 · PostgreSQL 17 + pgvector 0.8

---

## Run it

You need Docker and nothing else. Java and Maven are only needed if you want to run the
tests.

```bash
git clone <this-repo> && cd document-qa-rag
cp .env.example .env          # add OPENAI_API_KEY if you have one
docker compose up             # ~90s on first build, ~5s after
```

That starts Postgres with pgvector, waits for its healthcheck, runs the Flyway migrations
and serves the API on <http://localhost:8080>.

- Demo page: <http://localhost:8080/>
- Swagger UI: <http://localhost:8080/swagger-ui.html>
- Health: <http://localhost:8080/actuator/health>

Load the sample corpus (four realistic school documents in `samples/`):

```bash
./samples/load.sh              # tenant "greenwood"
./samples/load.sh shelbyville  # same corpus, second tenant, for the isolation demo
```

Then measure it. These two run against the live service with whatever provider you
configured, and are how the numbers in this README were produced:

```bash
python3 samples/calibrate.py   # similarity scores + threshold sweep
python3 samples/evaluate.py    # end-to-end: 20 labelled questions, and which gate fired
```

Then:

```bash
# grounded answer, with citations
curl -sS -X POST localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: greenwood' \
  -d '{"question":"What is the late fee if I pay term 2 three weeks late?"}'

# out of scope: refuses, and makes no model call at all
curl -sS -X POST localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: greenwood' \
  -d '{"question":"Who won the football world cup in 2022?"}'
```

**Without an API key the service still starts**, migrations run, uploads are accepted and
the API is browsable — only the calls that need a model report 503. That is deliberate: a
clean clone should never fail at boot for a reason unrelated to the code.

### Tests

```bash
./mvnw test      # 36 unit tests, no Docker needed
./mvnw verify    # + 37 integration tests against real Postgres + pgvector (needs Docker)
```

Tests pass with **no API key set** — the providers are switched off with
`spring.ai.model.*=none` and replaced by stubs, so no test can reach the network.

---

## See it working

Real output, captured from a running instance against `mistral-embed` +
`mistral-small-latest` and the four documents in `samples/`. Every number below is
copied verbatim from a terminal, not written by hand.

### A grounded answer, with a figure it had to work out

The policy states a rate — Rs 500 per week — and the question asks about three weeks.

```console
$ curl -sS -X POST localhost:8080/api/v1/chat \
    -H 'Content-Type: application/json' -H 'X-Tenant-Id: greenwood' \
    -d '{"question":"What is the late fee if I pay term 2 three weeks late?"}'

answer   : The late fee for paying term 2 three weeks late is Rs 1,500 [1].
refused  : false

sources:
  [1] Fee Policy 2026-27   sim=0.8632
      A late fee of Rs 500 per week, or part thereof, applies to any term fee
      received after its due date. The late fee is capped at Rs 4,000 per term...
  [2] Fee Policy 2026-27   sim=0.7905
  [3] Fee Policy 2026-27   sim=0.7626

retrieval 542ms | model 1028ms | 982 tokens in / 23 out
top similarity 0.8632 (threshold 0.75)
```

### An out-of-scope question — refused, with no model call

```console
$ ... -d '{"question":"Who won the football world cup in 2022?"}'

answer   : I could not find that in the available documents...
refused  : true
sources  : []
retrieval 61ms | model —        ← no model latency, because no model call happened
top similarity 0.6555 (threshold 0.75)
```

### A near miss — the case a threshold cannot catch

There is no hostel fee anywhere in the corpus, but the question is *about* fees and late
payment, so it scores 0.8069 — well above the bar. The second gate catches it.

```console
$ ... -d '{"question":"What is the late fee for the hostel accommodation charge?"}'

refused  : true      top similarity 0.8069 (threshold 0.75)   ← caught by the sentinel gate
```

### A follow-up that only works because it is rewritten first

```console
Q1: "What is the tuition fee for Class 9 in term 2?"   → The tuition fee for Class 9
                                                          in Term 2 is Rs 19,500 [1].
Q2: "And for Class 11 Science?"                        → The tuition fee for Class 11
                                                          Science in Term 2 is Rs 24,000 [1].
```

Server log for Q2 — the rewrite is what gets embedded, not the raw follow-up:

```
DEBUG QueryRewriter - Rewrote follow-up for retrieval:
      'And for Class 11 Science?' -> 'Tuition fee for Class 11 Science'
```

Raw, that question embeds at 0.7492 and is refused. Rewritten, 0.8466 and answered.

### Streaming: tokens, then sources as a distinct terminal event

```console
$ curl -N -X POST localhost:8080/api/v1/chat/stream ...

event order: token x7 -> sources -> done

sources: [1] Term 2 Examination Circular  sim=0.8108  available=true
done:    {"conversationId":"6942...","messageId":"5ac0...","refused":false}
```

### Deletion stops citations immediately

```console
$ curl -X DELETE localhost:8080/api/v1/documents/{id}     → 204
$ ... -d '{"question":"What is the late fee per week...?"}'
  refused: true | sources: 0
```

The conversation that cited it still shows the citation, flagged `available: false`.

### The whole grounding evaluation

```console
$ python3 samples/evaluate.py

  PASS  want=answer  got=answer  sim=0.8610  gate=-         late fee per week
  PASS  want=answer  got=answer  sim=0.7845  gate=-         route 4 departure time
  ...
  PASS  want=refuse  got=refuse  sim=0.6555  gate=threshold football world cup
  PASS  want=refuse  got=refuse  sim=0.8658  gate=sentinel  transport charge above 25 km
==================================================================================
  20/20 correct (100%)
  refusals: 3 by the threshold gate, 5 by the sentinel gate
```

### The test suite, with no API key set

```console
$ ./mvnw verify

Tests run: 36, Failures: 0, Errors: 0, Skipped: 0     (unit)
Tests run: 40, Failures: 0, Errors: 0, Skipped: 0     (integration, real pgvector)
All coverage checks have been met.
BUILD SUCCESS
```

---

## Architecture

Two paths. They meet only at the database.

```
INGESTION (async, off the request thread)

  POST /api/v1/documents
      │
      ├─ resolve extractor by extension ──▶ 415 if unsupported (before any I/O)
      ├─ stream to disk while SHA-256'ing (one pass, bounded memory)
      ├─ INSERT ... ON CONFLICT DO NOTHING on (tenant_id, content_hash)
      │      └─ already present? return the existing id, do not re-embed
      └─ enqueue ──▶ 202 Accepted { documentId, status: PROCESSING }
                          │
      ┌───────────────────┘   bounded queue (100) → 4 workers
      ▼
  extract      PDFBox per page / POI paragraphs+tables / Markdown headings
      │        → TextSegment(text, pageNumber, heading)
      ▼
  chunk        recursive split on ¶ → line → sentence → clause → word → tokens
      │        450 tokens, 80 overlap, never across a page or heading
      ▼
  embed        batched: ≤64 chunks and ≤200k tokens per call     ── network, no txn open
      │
      ▼
  persist      ┌─ BEGIN ─────────────────────────────────────┐
               │ DELETE old chunks · batch INSERT · → READY   │   ONE transaction
               └─ COMMIT ────────────────────────────────────┘

QUERY

  POST /api/v1/chat
      │
      ├─ load history, rewrite a follow-up into a standalone query   ← see below
      ├─ embed the question                                   (1 provider call)
      ├─ SQL: HNSW ANN ▸ tenant ▸ category ▸ threshold ▸ top-K  ── all in Postgres
      │
      ├── nothing cleared the threshold ──▶ REFUSE.  No model call. ◀── the gate
      │                                     Log the best near-miss score.
      │
      ├─ build prompt: system rules + token-budgeted history + numbered context
      ├─ call the model                                        (retry + circuit breaker)
      ├─ model emitted NOT_FOUND_IN_DOCUMENTS? ──▶ REFUSE      (second, softer gate)
      └─ persist turn + citation snapshot ──▶ { answer, sources[], metadata }
```

`POST /api/v1/chat/stream` is the same path, emitting SSE `token` events, then a single
`sources` event, then `done`. A client disconnect cancels the upstream model call.

### Layout

| Package | What lives there |
|---|---|
| `tenant` | `TenantId` and the header resolver. Start here. |
| `ingestion.extract` | One extractor per format; page/heading positions |
| `ingestion.chunk` | `RecursiveTokenChunker` — the chunking strategy |
| `ingestion` | Async pipeline, staging store, the transactional writer |
| `retrieval` | `VectorSearchRepository` — **the retrieval SQL. Read this one.** |
| `chat` | Prompt assembly, the refusal gates, conversation memory |
| `model` | Provider wrappers: batching, retry, circuit breaker, tokens |
| `document` | Upload/list/delete API |
| `observability` | Correlation IDs, metrics, model health |

---

## Chunking strategy

**Page-aware recursive splitting, 450 tokens with 80 tokens (~18%) of overlap, measured in
real BPE tokens.** `RecursiveTokenChunker` is ~200 lines and the whole strategy is in it.

**1. Chunks never cross a page or section boundary.** This rule comes first and everything
else bends around it. A chunk spanning pages 3–4 cannot be cited honestly — you either
claim page 3 and are wrong half the time, or say "pages 3–4" and make the administrator
read both. The entire value of this service is a citation someone can check in ten
seconds, so exact provenance beats the marginal recall gain from letting chunks straddle
boundaries. The cost is real and listed under Known limitations.

**2. Split on the largest semantic boundary that fits**: paragraph → line → sentence →
clause → word → raw token slice, descending only when a piece is still too big. Standard
recursive splitting, but **measured in tokens, not characters**. That is not pedantry:
Indian fee documents are dense in digits, currency symbols and abbreviations, which
tokenise at roughly 2 characters per token rather than the usual 4. The common
"1800 chars ≈ 450 tokens" assumption would produce ~900-token chunks on exactly the
documents this service targets.

**3. Why 450 and 80.** School policy is written as short numbered clauses under a heading.
450 tokens holds a heading plus two or three complete clauses, so "the late fee for term 2"
lands on a chunk containing both the qualifier and the number.

- *Smaller (100–200)* scores higher on raw similarity but routinely returns the amount
  without the condition attached — which is how a wrong figure reaches a parent.
- *Larger (1000+)* dilutes the embedding toward the average topic of the page. Near-miss
  questions start clearing the threshold and the refusal path stops firing when it should.
- *80 tokens of overlap* is about one full clause, so a definition landing on a boundary
  survives whole in at least one of the two neighbours.

**4. The embedded text is not the stored text.** Each chunk stores `content` (exactly what
the document says — used for the citation snippet and the prompt) and separately embeds
`"<title> > <heading>\n\n<content>"`. A clause like *"A late fee of Rs 500 per week
applies"* is nearly unretrievable alone, because "term 2", "fee policy" and "class" — how
a parent actually asks — appear only in the heading above it. Embedding the heading with
the clause fixes retrieval without ever showing the model synthesised text it could quote
as if the document said it.

**5. Tables are flattened row-wise with the header repeated.** Read cell by cell, a fee
table gives you `Class 9` and `5200` as unrelated fragments and neither is answerable.
`Class | Term 2 fee — Class 9 | 5200` keeps the label attached to the number inside one
chunk. Fee schedules and transport routes — the documents this exists for — are mostly
tables.

Undersized trailing fragments are merged into their predecessor: a 9-token tail embeds to
noise and, being short, scores deceptively high on unrelated queries.

---

## Embedding model, dimensions and cost

| | |
|---|---|
| Model | `text-embedding-3-small` (OpenAI), configurable |
| Dimensions | 1536 |
| Chat model | `gpt-4.1-mini`, temperature 0 |
| Batching | ≤64 chunks **and** ≤200,000 tokens per call, whichever binds first |

**Cost per 1000 pages ingested: about 1.2 US cents.**

Working, so you can check the assumptions rather than trust the number:

- A typical A4 policy page ≈ 500 tokens → 1000 pages ≈ 500,000 tokens.
- 18% overlap duplication → ≈ 590,000 tokens actually embedded.
- ~1,300 chunks × ~15 tokens of title/heading prefix → ≈ 609,000 tokens.
- At $0.02 / 1M tokens → **$0.0122**.

Per question at query time: one embedding call (~20 tokens, effectively free) plus one chat
call at ~4,500 input and ~200 output tokens → **≈ $0.0021, about 0.2 cents**. A refused
question costs one embedding call and nothing else, because no model call is made.

Prices are from the time of writing and live in `rag.cost.*` config, not code, so a wrong
number here costs you a wrong dashboard and nothing else.

**Changing the embedding model is a schema change, not a config change.** pgvector fixes
the dimension in the column type. The dimension is a Flyway placeholder
(`vector(${embeddingDimensions})`) so one migration serves any model, and
`EmbeddingDimensionValidator` **fails startup** if the configured model disagrees with the
column. Without that check, swapping to a 768-dimension model against a 1536 column gives
you either cryptic mid-batch insert failures or — if two models happen to share a
dimension — a table containing vectors from two incompatible embedding spaces, where every
score is meaningless and nothing anywhere throws.

---

## Similarity threshold

**The headline result: a similarity threshold cannot separate a near-miss question from a
real one, and I have the measurements to prove it.** That finding shaped the design, so it
is worth walking through.

`samples/calibrate.py` points a labelled question set at a running instance — 12 questions
the sample corpus genuinely answers, 8 it does not — and reports the top similarity each one
achieves. Against `mistral-embed`:

```
ANSWERABLE  (want these ABOVE the bar)     UNANSWERABLE (want these BELOW)
  0.7845  route 4 bus departure time         0.8658  transport charge above 25 km
  0.7863  re-evaluation charge               0.8304  study leave entitlement
  0.7981  fees unpaid after 45 days          0.8290  pre-nursery playgroup fee
  ...                                        0.8069  late fee on hostel charge
  0.8721  casual leave days                  0.7630  staff medical insurance
  0.8755  transport charge 10-15 km          0.7097  reset my portal password
                                             0.6677  capital city of Australia
                                             0.6555  who won the world cup
lowest answerable   : 0.7845
highest unanswerable: 0.8658          separation: -0.0813   ← the sets OVERLAP
```

The four highest-scoring *unanswerable* questions all outscore the lowest *answerable* one.
That is not a tuning failure — it is what the embedding is telling us, and it is correct:
"what is the transport charge for a distance above 25 km?" really is nearly identical, as
text, to a document that lists transport charges by distance band. It just stops at 15 km.
No scalar threshold can distinguish "this document is about your question" from "this
document answers your question".

**So the threshold is set to do the job it can actually do.** At **0.75** it keeps all 12
answerable questions and rejects the three genuinely off-topic ones outright, for free, with
no model call. The near-misses are the second gate's job — and that is precisely why the
second gate exists rather than being decoration on top of the first.

End-to-end, with both gates in play (`samples/evaluate.py`):

```
20/20 correct (100%)
refusals: 3 by the threshold gate, 5 by the sentinel gate
```

Every one of the five that slipped past the threshold was caught by the model reporting
insufficient context. Neither gate is sufficient alone; the split of labour is the design.

**Reproduce it in two minutes** — the service must be running with the corpus loaded:

```bash
./samples/load.sh greenwood
python3 samples/calibrate.py     # scores + threshold sweep
python3 samples/evaluate.py      # end-to-end pass/fail, and which gate fired
```

### The threshold is model-specific

`0.75` is measured for **`mistral-embed`**. Do not carry it to another model. Embedding
models differ enormously in where they put unrelated text — Mistral's floor for
genuinely off-topic questions sits around 0.65, where OpenAI's `text-embedding-3-small`
typically lands far lower. The configured default of **0.62 assumes OpenAI and is not
measured**; if you run this on OpenAI, spend the two minutes above and set your own.

There is also a third source of the same signal, with no harness at all: **every refusal logs
the best chunk that did not clear the bar**, so a week of production logs tells you whether
the threshold is wrong.

```
Refusing: no chunk cleared 0.75. Closest was Fee Policy 2026-27 p2 at similarity 0.7097
```

`ThresholdCalibrationIT` runs the same sweep offline against the stub embedder on every
build. It cannot produce a meaningful *number* — the stub is lexical — but it asserts
monotonicity, which would break immediately if the threshold stopped being applied in SQL.

---

## Tenant isolation

Four independent layers. The brief says this is specifically tested, so:

**1. It is a parameter, not ambient state.** Every repository method takes a `TenantId` as
its first argument and every statement filters on it. There is no unscoped overload. The
alternative — a `ThreadLocal` or `ScopedValue` read deep in the data layer — reads more
cleanly at the call site and is exactly how this leak usually happens: the ingestion
executor runs on a different thread where the context is empty, or worse, holds whatever
the previous task on that pooled thread left behind. Explicit parameters have no
thread-affinity failure mode.

**2. It is in the query, not after it.** See the next section.

**3. It is in the schema.** `document_chunks.tenant_id` is denormalised so the vector query
needs no join. Denormalised columns drift, so it is not maintained by application code
alone:

```sql
FOREIGN KEY (document_id, tenant_id) REFERENCES documents (id, tenant_id) ON DELETE CASCADE
```

A chunk whose tenant differs from its parent document is **physically unrepresentable**.
There is a test that tries to insert one with raw SQL, bypassing every line of application
code, and asserts Postgres rejects it.

**4. Cross-tenant ids are 404, never 403.** A 403 would confirm that another tenant's
document id exists.

The category filter is the only caller-controlled value reaching the retrieval query. It is
a bind parameter; `TenantIsolationIT` fires `FEES' OR '1'='1` and friends at it and asserts
nothing leaks.

---

## Retrieval happens in the database

`VectorSearchRepository` is one CTE and one join. Tenant, category, document status, top-K
and the similarity threshold are **all applied by Postgres**. Nothing is fetched and then
discarded in Java.

That is not a style preference. Post-filtering a K-row ANN result by tenant returns fewer
than K rows — sometimes zero — because the index returned the globally nearest chunks, most
of which belong to somebody else. The system then refuses a question it could have
answered, and the bug only appears once a second tenant has data. (It also ships ~1.2 MB of
1536-float embeddings per question to throw 195 of 200 rows away.)

The ANN scan sits **alone in the CTE**, over `document_chunks` only. Joining `documents`
inside it gives the planner a reason to prefer a hash join with a sequential scan over the
HNSW index — the exact failure NFR-6 prohibits. The join happens outside, against at most
`candidateLimit` rows.

### The index is used — measured, not assumed

HNSW rather than IVFFlat because IVFFlat needs a populated table to build meaningful
centroids, and this table starts empty on a clean clone; an IVFFlat index built at
migration time on zero rows is worse than none. `vector_cosine_ops` matches the `<=>`
operator in the query — a mismatched opclass silently produces a sequential scan.

I checked the plan at two scales:

| Corpus | Plan | Time |
|---|---|---|
| 2,000 chunks/tenant (6k total) | Bitmap heap scan + sort | ~15 ms |
| 12,000 chunks/tenant (36k total) | **Index Scan using `document_chunks_embedding_hnsw_idx`** | **1.8 ms** |

The planner is *right* in the first case: sorting 2,000 rows genuinely beats an HNSW walk
with post-filtering. At the corpus size NFR-1 actually specifies (200 documents ≈ 10k+
chunks) it switches to the index, and retrieval lands at 1.8 ms — the rest of NFR-1's
500 ms budget is the embedding API round trip.

### Filtered HNSW search

pgvector's HNSW walks the graph and applies `WHERE` to what it finds. Under a selective
filter it can exhaust its candidate list before finding `LIMIT` matching rows and quietly
return short. Two settings, applied per transaction with
`set_config(..., is_local => true)` so they cannot leak onto a pooled connection:

- `hnsw.ef_search = 100` — widens the search beam (default 40).
- `hnsw.iterative_scan = relaxed_order` — pgvector 0.8+; lets the scan resume rather than
  stop short. `set_config` on an unknown GUC is harmless, so the same code runs on older
  builds with slightly worse recall.

---

## Failure handling

Retry and circuit breaking are wired **programmatically**, not with
`@CircuitBreaker` annotations. There are exactly two protected call sites, and the
annotation route needs AOP proxies — a self-invocation silently loses the protection, and
that is invisible until production. Chat and embeddings get separate breakers: under a
swapped provider they can be different vendors, and an embedding rate-limit must not take
down question answering.

The breaker trips on **slow calls**, not only failures (`slowCallDurationThreshold`). A
model provider rarely fails outright; it goes slow first, and a pile-up of 60-second
requests exhausts the pool long before the error rate moves.

**A refused request is not an outage.** `ModelErrors` separates "the provider is down"
(5xx, timeouts, connection failures → retry with jittered exponential backoff, breaker
watches) from "the provider understood and refused" (4xx except 429 → do not retry, do not
count toward the breaker). Getting this wrong is what produced the worst first-run
experience this service had — starting with no API key gave:

> The embedding provider is currently unavailable and requests are being rejected while it
> recovers. Try again shortly.

…which is wrong on both counts, took three retries and 8s of backoff to reach, and opened
the circuit so `/actuator/health` blamed OpenAI for an unset environment variable. It now
says:

> The embedding provider rejected the request. This usually means the API key is missing or
> invalid, or the configured model name does not exist — check the relevant `*_API_KEY` and
> `*_MODEL` environment variables.

…on the first attempt, with the breaker still CLOSED and health still UP.

Reading the status code is done **reflectively**, and that is deliberate. Spring AI 2
delegates to each vendor's own SDK — OpenAI failures arrive as
`com.openai.errors.NotFoundException` — and those hierarchies share no common supertype.
Importing a vendor class to read the status would put a vendor name in the code, which the
"swappable via config, not code changes" requirement forbids. An unrecognised exception is
treated as transient: the conservative default, costing two extra retries rather than
silently giving up on a real outage.

Everything else that can go wrong maps to a status rather than a stack trace: 415 unsupported
type, 413 too large, 400 no tenant header, 404 unknown or other-tenant id, 503 with
`Retry-After` for provider failure and for a saturated ingestion queue. Responses are RFC 9457
`application/problem+json` and every one carries the correlation id.

---

## Follow-up questions

FR-7 asks that follow-ups like *"what about for class 9?"* work. Putting conversation
history in the prompt is necessary but **not sufficient**, and the reason is a sequencing
problem that is easy to miss: **retrieval runs before the model does.**

I found this by running the service, not by reading the code:

```
Q: "What is the tuition fee for Class 9 in term 2?"   → ₹19,500 [1]      ✅
Q: "And for Class 11 Science?"                        → REFUSED          ❌  sim 0.7492
```

The second question was refused with the answer sitting in the corpus and the history
sitting in the prompt, unread — because the string that got embedded was the bare
follow-up, eight words mentioning neither fees nor terms. It scored 0.7492, below the
threshold, and the refusal fired before the model was ever called.

`QueryRewriter` resolves the follow-up against the last two turns before embedding:

```
'And for Class 11 Science?'  →  'Tuition fee for Class 11 Science'   sim 0.8466  → ₹24,000 ✅
'What about term 3?'         →  'Tuition fees for Class 9 and Class 11 Science in Term 3' ✅
```

Three things about the design:

- **It costs one extra small model call, on turns that have history only.** A first question
  short-circuits with no call at all.
- **If the rewrite fails, we fall back to the user's original question** rather than failing
  the request. Degraded retrieval beats no answer, and the refusal path still protects
  correctness if the degraded retrieval finds nothing.
- **The rewrite is used for retrieval only.** The prompt, the stored history and the API
  response all carry the user's own wording — rewriting what somebody asked and then showing
  it back to them is disorienting.

---

## Transaction boundaries

One transaction per document (FR-2), and it covers **exactly** the writes:

```
extract → chunk → embed          ← no transaction open (seconds of third-party HTTP)
    ↓
BEGIN
  DELETE old chunks
  batch INSERT chunks + embeddings
  UPDATE documents SET status = 'READY'
COMMIT
```

Wrapping the whole ingestion in one `@Transactional` method would be simpler and wrong:
embedding a 50-page PDF is several seconds of network I/O, and holding a pooled connection
across it means a burst of uploads exhausts the pool and takes question answering down with
it. The tradeoff is that a crash after embedding but before commit loses the embedding
work. That is the right side of the trade — the work is recomputable, the connection pool
is not.

Because the status flip commits **with** the chunks, a document is never observable as
READY with partial chunks, nor as PROCESSING with chunks already queryable. Retrieval can
rely on "READY means complete" without taking a lock.

The writer lives in its own bean (`IngestionWriter`) rather than as a method on
`IngestionService`, because Spring's `@Transactional` is proxy-based: a self-invocation
from another method of the same class bypasses the proxy and silently runs with no
transaction at all.

---

## Testing

**73 tests. 86% line coverage overall** (the build fails below 60% on service packages),
plus a 20-question end-to-end grounding evaluation that runs against a live provider
(`samples/evaluate.py`) and currently scores **20/20**.

```
36 unit          chunking boundary cases, extractors, error classification — no Docker
37 integration   real Postgres + real pgvector via Testcontainers
```

Not H2. H2 has no `vector` type, no `<=>` operator, no HNSW index and no `set_config`, so
an H2 "integration" test would exercise none of the code this system's correctness depends
on.

The assertions worth knowing about:

- **`TenantIsolationIT`** — two tenants upload documents *identical apart from one number*,
  so their embeddings are equally similar to the question and any weak or late filter lets
  the wrong figure through. Also asserts the composite FK rejects a smuggled chunk, and
  that crafted category filters cannot widen scope.
- **`RefusalPathIT`** — asserts `chatModel.callCount() == 0` on an out-of-scope question.
  Checking only the response text would pass for a system that calls the model, gets a
  hallucination and discards it; counting the calls is the only proof the gate is *in front
  of* the model.
- **`StreamingAndMemoryIT`** — asserts the upstream `Flux` actually receives a cancel signal
  when the client hangs up, that `sources` is the second-to-last event, and that a refusal
  sentinel never reaches the client as visible text.
- **`ThresholdCalibrationIT`** — the sweep above; also asserts monotonicity, which would
  break instantly if the threshold were not applied in SQL.

The stub embedder is a hashed bag of words, L2-normalised — **not** random or constant
vectors. Random vectors make everything refuse and constant vectors make nothing refuse; in
both cases the threshold tests pass for the wrong reason and would keep passing if the
threshold logic were deleted.

---

## Configuration

Everything is in `application.yml` under `rag.*`, validated at startup — a typo or a
nonsensical value fails the context refresh with a readable message instead of surfacing
hours later as bad retrieval.

Provider selection is **config, not code**. No class names a vendor:

```bash
RAG_CHAT_PROVIDER=mistral         # openai | mistral | google-genai | anthropic | ollama | none
RAG_EMBEDDING_PROVIDER=mistral    # openai | mistral | ollama | none
```

### Running it free, with no card

| Provider | Chat | Embeddings | Notes |
|---|:---:|:---:|---|
| **Mistral** | ✅ free | ✅ free | One key covers both. `mistral-embed` is **1024** dims, so set `RAG_EMBEDDING_DIMENSIONS=1024` and rebuild the DB. |
| **Google Gemini** | ✅ free | ❌ | Free AI Studio key works for chat. Spring AI 2.0's Google embedding module is **Vertex AI** based and needs a GCP project with billing — pair Gemini chat with Mistral or Ollama embeddings. |
| **Ollama** | ✅ local | ✅ local | No key, no network. ~2.3 GB of model download. `nomic-embed-text` is **768** dims. |
| **OpenAI** | paid | paid | The default. ~1.2¢ per 1000 pages, ~0.2¢ per question. |

`.env.example` has a copy-paste block for each. Note that switching embedding provider
changes the vector dimension, which is a schema change — `docker compose down -v && docker
compose up` rebuilds it, and the app refuses to start against a mismatched column rather
than silently mixing two embedding spaces.

**A wrinkle worth knowing about.** Several of Spring AI 2.0's provider autoconfigurations are
gated only on the jar being on the classpath, not on that provider being *selected*. Mistral's
moderation and OCR autoconfigurations both instantiate eagerly and throw `Mistral API key must
be set` at context refresh — so merely adding the Mistral starter broke startup even with
`spring.ai.model.chat=openai`. Both are excluded in `application.yml`; this service does
neither moderation nor OCR. The chat and embedding autoconfigurations are correctly gated and
stay. Google's embedding connection has the same shape, demanding a GCP `project-id`
unconditionally, which is why only the Gemini *chat* starter is included.

| Setting | Default | Notes |
|---|---|---|
| `rag.retrieval.top-k` | 5 | |
| `rag.retrieval.similarity-threshold` | 0.62 | see above |
| `rag.chunking.max-tokens` / `overlap-tokens` | 450 / 80 | |
| `rag.chat.max-history-turns` | 6 | |
| `rag.chat.history-token-budget` | 1200 | whichever binds first |
| `rag.embedding.batch-size` | 64 | |
| `rag.ingestion.worker-threads` / `queue-capacity` | 4 / 100 | bounded on purpose |

**Conversation history is capped by token budget as well as turn count.** Turn count alone
is not a budget: six turns of "what time does the bus leave?" is 60 tokens; six turns where
the assistant quoted a fee table is several thousand, and both land in the same fixed
window. Under the second case the retrieved context — the part that actually grounds the
answer — gets pushed out, and the model starts answering from conversation history instead
of from documents. History is walked backwards from the newest turn so that when the budget
runs out the *oldest* turns are dropped, which is where a follow-up like "what about for
class 9?" gets its referent.

Refused turns are excluded from history entirely: they carry no usable information and
several in a row bias the model toward refusing again.

---

## Known limitations

Things I chose not to do, or could not verify. In rough order of how much they would
bother me in production.

1. **The default threshold (0.62) assumes OpenAI and is not measured.** The measured value
   is 0.75, for `mistral-embed`, which is what I ran against. Thresholds do not transfer
   between embedding models. `samples/calibrate.py` produces the right number for whatever
   provider you configure in about two minutes.
2. **A similarity threshold cannot catch near-misses at all** — measured, not assumed; see
   the Similarity threshold section. The model-sentinel gate covers them and gets 5/5 on
   the evaluation set, but it is a prompt instruction, so it is best-effort rather than a
   guarantee. A reranker or a small entailment check would make it a stronger gate; that is
   the top item on the two-week list.
3. **Chunks never span pages, so a clause split across a page break is weaker in
   retrieval.** Deliberate — see Chunking. With more time I would keep the hard page
   attribution but add a small "bridge" chunk carrying the tail of page N and head of N+1,
   cited as a range and clearly labelled.
4. **Retrieval measured 542 ms end to end**, just over NFR-1's 500 ms. The database half is
   ~2 ms; effectively all of it is the embedding provider's HTTP round trip. Caching query
   embeddings would help repeat questions, but the honest fix is a provider in the same
   region, or a local embedding model.
5. **The ingestion queue is in memory.** `kill -9` loses queued-but-unstarted jobs and
   leaves those documents in PROCESSING forever. Graceful shutdown drains in-flight work,
   and re-uploading recovers a stuck document. A real fix is an outbox table polled by the
   workers, which also gives you multi-instance ingestion.
6. **Scanned PDFs are rejected, not OCR'd.** The error message says so explicitly rather
   than silently ingesting zero chunks.
7. **DOCX has no page numbers** — Word paginates at render time, so there is no page number
   in the file. Citations report `null` rather than inventing one. Rendering via headless
   LibreOffice would recover it.
8. **No authentication.** `X-Tenant-Id` is a plain header, as the brief permits. In
   production it must come from a signed token; today any caller can name any tenant. The
   isolation machinery is exactly the same either way — only the source of the value
   changes — but this is a header, not a security boundary.
9. **Categories are free-form**, normalised to upper case rather than validated against an
   enum. Hard-coding FEES/HR/EXAM/TRANSPORT would mean a code change to file an
   "ADMISSIONS" document. Case normalisation is the part that matters: without it "fees"
   and "FEES" become two categories and a filter silently returns nothing.
10. **`GET /conversations/{id}` is unpaginated.** Fine for a support conversation, wrong for
   one that has run for a year.
11. **No `DELETE /conversations/{id}`**, and no retention policy on stored questions —
    which are personal data in a school context.

### With two more weeks

In priority order: calibrate the threshold properly against a real provider and a bigger
golden set, with the sweep running on every build so retrieval regressions are visible in
CI. Move the ingestion queue to an outbox table. Add re-ranking of retrieved chunks before
prompt assembly — the cheapest remaining accuracy win, since the top-5 by cosine is often
mis-ordered. Then hybrid retrieval, fusing pgvector with Postgres full-text search, for
questions containing rare exact tokens ("Form 12BB", "Class IX-B") where embeddings are
weakest — with the important constraint that fusion may reorder candidates but must not
admit anything below the vector threshold, or the refusal guarantee quietly dies.

---

## One thing that surprised me

**That the similarity threshold — the mechanism I had assumed was the refusal system —
provably cannot do the job on its own, and I only found out because I measured it.**

I built the calibration harness expecting it to hand me a number. Instead it showed the two
question sets *overlapping*: the highest-scoring question the corpus cannot answer
("what is the transport charge for a distance above 25 km?", 0.8658) scores higher than the
lowest-scoring question it can ("what time does the route 4 bus depart?", 0.7845). There is
no threshold that keeps one and rejects the other. Turning the dial up loses real answers
before it stops the wrong ones.

The reason is obvious in hindsight and I had not thought it through: cosine similarity
measures whether a chunk is *about* your question, not whether it *answers* it. A table of
transport charges by distance band is maximally about a question asking for the charge at
25 km. It simply stops at 15 km. That is a fact about the document's content, not its
embedding, and no amount of tuning surfaces it.

What makes this more than an interesting negative result is that it changed how I read my
own design. I had written the model-sentinel gate as a belt-and-braces afterthought behind
the "real" threshold gate. The measurements say it is the other way round: the threshold
catches the easy cases cheaply — three genuinely off-topic questions, refused for free with
no model call — and every one of the five hard cases is caught by the sentinel. The gate I
thought was decoration is doing the load-bearing work, which is also why "it is only a
prompt instruction, so it is best-effort" is now the first thing on my list of things to
fix rather than a caveat I would have mentioned in passing.

Two smaller ones. The query planner *correctly* refused to use my HNSW index at 2,000 rows
and only chose it at 12,000 — I had assumed "index exists" meant "index is used" and was
ready to file a bug against my own migration. And a surprising amount of this system's
correctness ended up living in DDL rather than code: the composite foreign key
`(document_id, tenant_id) → documents(id, tenant_id)` turns tenant isolation from something
every query must remember into something Postgres will not let me get wrong.
