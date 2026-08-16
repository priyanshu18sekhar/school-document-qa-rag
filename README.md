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

**Configured: 0.62** (cosine, `rag.retrieval.similarity-threshold`).

I did not pick this by feel. `ThresholdCalibrationIT` ingests the sample corpus, runs 10
questions the corpus **does** answer and 6 it **does not** — three of them deliberate near
misses that use the corpus's own vocabulary to ask about something it never states — and
sweeps the threshold, reporting how many of each get through at each value.

```
threshold   answerable found   unanswerable leaked   verdict
0.50        3 / 10             2 / 6                 answers questions it should refuse
0.55        2 / 10             1 / 6                 answers questions it should refuse
0.60        2 / 10             1 / 6                 answers questions it should refuse
0.62        2 / 10             0 / 6                 no leakage          ← chosen
0.65        2 / 10             0 / 6                 no leakage
0.70        1 / 10             0 / 6                 refuses 9 answerable
```

**0.62 is the lowest threshold at which no out-of-scope question gets through.** That is
the number that matters: below it, the near-miss questions ("what is the late fee for the
*hostel* charge?", a fee the policy never mentions) start retrieving the general late-fee
clause and the model would answer from it. Going higher buys no additional safety and only
costs recall.

**Be honest about what this measurement is.** The run above is against the offline stub
embedder used in tests, which matches on shared words rather than meaning. Its *absolute*
values are not the values a real embedding model produces, and its recall column is
correspondingly poor — it cannot connect "how many days of casual leave" to a table row
reading `Casual leave | 12 days`. **I had no API key while building this, so 0.62 is not
yet validated against real embeddings.** What is validated is the method, the harness and
the fact that the threshold is genuinely applied by the query (the sweep asserts
monotonicity — recall and leakage can only fall as the bar rises, which would break
immediately if the threshold were not in the SQL).

To calibrate properly, one command:

```bash
OPENAI_API_KEY=sk-... ./mvnw verify -Dit.test=ThresholdCalibrationIT \
    -Dspring.ai.model.embedding=openai
```

My expectation is that a real model lands somewhat higher, around 0.70–0.75, because
semantic embeddings put unrelated text closer to 0.5 than a lexical model does. I would
re-run the sweep and take the lowest zero-leakage value rather than assume.

Production gives you the same signal without the harness: **on every refusal the service
logs the best-scoring chunk that did not clear the bar**, so a week of logs tells you
whether the threshold is too high:

```
Refusing: no chunk cleared 0.62. Closest was Fee Policy 2026-27 p2 at similarity 0.5841
```

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

**73 tests. 86% line coverage overall** (the build fails below 60% on service packages).

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

1. **The threshold is not calibrated against real embeddings.** I had no API key. The
   harness, the method and the near-miss logging are all in place; the number needs one
   command and a key. This is the single biggest gap.
2. **No end-to-end run against a live provider.** Every layer is exercised by tests, but
   the actual HTTP call to OpenAI is stubbed. The wire call is Spring AI's code, not mine,
   but I have not seen it work with my own eyes and I am not going to claim otherwise.
3. **Chunks never span pages, so a clause split across a page break is weaker in
   retrieval.** Deliberate — see Chunking. With more time I would keep the hard page
   attribution but add a small "bridge" chunk carrying the tail of page N and head of N+1,
   cited as a range and clearly labelled.
4. **The ingestion queue is in memory.** `kill -9` loses queued-but-unstarted jobs and
   leaves those documents in PROCESSING forever. Graceful shutdown drains in-flight work,
   and re-uploading recovers a stuck document. A real fix is an outbox table polled by the
   workers, which also gives you multi-instance ingestion.
5. **Scanned PDFs are rejected, not OCR'd.** The error message says so explicitly rather
   than silently ingesting zero chunks.
6. **DOCX has no page numbers** — Word paginates at render time, so there is no page number
   in the file. Citations report `null` rather than inventing one. Rendering via headless
   LibreOffice would recover it.
7. **No authentication.** `X-Tenant-Id` is a plain header, as the brief permits. In
   production it must come from a signed token; today any caller can name any tenant. The
   isolation machinery is exactly the same either way — only the source of the value
   changes — but this is a header, not a security boundary.
8. **Categories are free-form**, normalised to upper case rather than validated against an
   enum. Hard-coding FEES/HR/EXAM/TRANSPORT would mean a code change to file an
   "ADMISSIONS" document. Case normalisation is the part that matters: without it "fees"
   and "FEES" become two categories and a filter silently returns nothing.
9. **`GET /conversations/{id}` is unpaginated.** Fine for a support conversation, wrong for
   one that has run for a year.
10. **No `DELETE /conversations/{id}`**, and no retention policy on stored questions —
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

**How much of the correctness lives in the schema rather than the code.**

I expected the interesting problems to be chunk sizes and prompt wording. The two changes
that most improved this system were both DDL. The composite foreign key
`(document_id, tenant_id) → documents(id, tenant_id)` turns tenant isolation from a
property I have to maintain in every query into one Postgres refuses to let me violate —
a whole class of bug deleted by four lines of SQL. And making the embedding dimension a
Flyway placeholder with a startup check turned the nastiest possible misconfiguration
(vectors from two different models in one table, every score meaningless, nothing throwing
anywhere) into a boot failure with a message that tells you what to do.

A close second: watching the query planner *correctly* refuse to use my HNSW index at 2,000
rows, and only choose it at 12,000. I had assumed "index exists" meant "index is used", and
was ready to file that as a bug in my own migration. Measuring the plan at two scales was
the difference between a wrong conclusion and understanding where the crossover actually
is.
