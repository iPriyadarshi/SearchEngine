# Search Engine 

A **modular, extensible mini search engine** built from scratch in Java to understand the fundamentals of **Information Retrieval (IR)** systems.

This project implements:

- document parsing pipeline (tokenizer + filters + Porter stemmer)
- inverted index and positional inverted index
- ranking algorithms (TF-IDF, Cosine, BM25)
- query processing (ranked, phrase, and boolean queries)
- disk persistence of the index
- a disk-based, memory-mapped index that scales to Wikipedia-sized corpora
- MaxScore top-k retrieval, so queries stay sub-millisecond on hundreds of thousands of articles
- a streaming Wikipedia dump reader with parallel bzip2 decompression
- HTML parsing and a small web crawler
- an interactive CLI, a REST/HTTP search API, and a responsive web UI
- extensible architecture using SOLID principles

---

# Project Goals

This project is designed as a **learning-focused search engine** to understand how real search engines work internally.

We aim to explore:

* text parsing and normalization
* inverted index design
* ranking algorithms
* search efficiency
* storage tradeoffs
* extensible software architecture

---

# Features

## Core Features

### Document Processing
* load documents from folder
* tokenize text
* normalize tokens
* remove stopwords
* configurable processing pipeline

### Indexing
* inverted index
* term frequency tracking
* document frequency tracking
* efficient lookup structure

### Ranking
* TF-IDF scoring
* Cosine (tf-idf weighted) scoring
* BM25 scoring with document-length normalization

### Query Engine
* ranked keyword queries
* phrase queries (via positional index)
* boolean queries (AND / OR / NOT with parentheses)
* ranked results
* fast lookup

---

# Architecture

The system is designed using **SOLID principles** and common **design patterns**.

## Design Goals

* extensible
* testable
* modular
* clear separation of concerns
* easy experimentation

---

# High-Level Pipeline

```
  Documents
      ↓
Parser Pipeline
      ↓
    Tokens
      ↓
 Inverted Index
      ↓
Ranking Algorithm
      ↓
 Search Results
```

---

# Module Overview

```
com.searchengine
├── api/            core interfaces (Index, Ranker, Parser, Tokenizer, TokenFilter,
│                   QueryParser, DocumentSource, DocumentLengthProvider, DocumentIdProvider)
├── parser/         tokenizer, filters, and the Porter stemmer
├── document/       Document model and sources (folder, HTML, Wikipedia dumps)
├── index/          memory and positional inverted indexes + posting models
│   └── disk/       SPIMI index writer, block postings, memory-mapped reader
├── ranking/        TF-IDF, Cosine, and BM25 rankers
├── query/          query models, parsers, and executors (ranked, phrase, boolean)
│   └── disk/       MaxScore top-k, phrase and boolean search over the disk index
├── engine/         SearchEngine facade + result formatting
├── search/         SearchBackend: one interface over the memory and disk engines
├── storage/        disk persistence (IndexStore)
├── crawler/        web crawler + pluggable page fetcher
├── http/           REST search API + responsive web UI over the JDK HttpServer
├── Main               command line entry point
├── BuildIndexCommand  --build-index
└── BenchmarkCommand   --bench

resources/web/      single-page web UI (HTML + CSS + JS)
```

---

# Design Principles

## SOLID

### Single Responsibility Principle
Each component has one responsibility.

Examples:

Tokenizer → splits text  
TokenFilter → transforms tokens  
Parser → coordinates pipeline  

---

### Open/Closed Principle

Add new filters or ranking algorithms without modifying existing code.

Example:

```
add PorterStemmerFilter
add BM25Ranker
```

---

### Liskov Substitution Principle

Interfaces allow interchangeable components.

```
Tokenizer
RegexTokenizer
WhitespaceTokenizer
```

---

### Interface Segregation Principle

Small focused interfaces:

```
Tokenizer
TokenFilter
Parser
Ranker
```

---

### Dependency Inversion Principle

High-level modules depend on abstractions:

```
Parser depends on Tokenizer interface
not concrete implementation
```

---

# Design Patterns Used

## Strategy Pattern

Ranking algorithms:

```
Ranker
├── TFIDFRanker
├── CosineSimilarityRanker
└── BM25Ranker
```

---

## Pipeline Pattern

Text processing pipeline:

```
Tokenizer → Filters → Tokens
```

---

## Iterator Pattern

Postings, AND / OR / NOT combinations of postings, and query execution all
share the `DocIdIterator` contract (`nextDoc`, `advance`), so they compose
lazily without materializing document sets.

---

## Repository Pattern

Index acts as data access layer.

---

# Parsing Pipeline

Example:

```
Input text:

"This is a Search Engine"

Tokenizer:

["This", "is", "a", "Search", "Engine"]

Filters:

lowercase
remove stopwords
remove short words

Output:

["search", "engine"]
```

---

# Inverted Index

Maps terms to documents.

Example:

```
search → doc1, doc4
engine → doc1, doc2
information → doc3
```

With term frequency:

```
search → (doc1, tf=2)
engine → (doc2, tf=5)
```

---

# Ranking Algorithms

## Term Frequency (TF)

Score based on occurrences of term in document.

```
score(doc) = term frequency
```

---

## TF-IDF

Balances term frequency with rarity of term.

Rare terms get higher importance.

```
tfidf = tf * log(N / df)
```

Where:

N = number of documents  
df = number of documents containing term  

---

# Scaling to Wikipedia: the Disk Index

The in-memory index keeps every posting as Java objects, which needs well over
100 GB of heap for English Wikipedia. The disk index (`index.disk`,
`query.disk`) keeps the heap small and lets the OS page cache hold the data.

## Building (SPIMI + k-way merge)

```
dump ─▶ reader thread ─▶ worker pool ─▶ DiskIndexWriter ─▶ seg-00000.tmp ┐
        (XML; bz2 on     (clean +       (per-term varint    seg-00001.tmp ├─▶ k-way merge ─▶ final index
         its own pool)    analyze)       buffers)            ...           ┘
```

1. **Read**: `WikipediaDumpSource` pulls one page at a time with StAX.
   Multistream `.bz2` dumps are cut at stream boundaries and decompressed in
   parallel (`ParallelBzip2InputStream`).
2. **Analyze**: `ParallelIndexer` cleans wikitext and runs the parser on a
   worker pool, handing results back in order, so a parallel build is
   byte-identical to a sequential one.
3. **Accumulate**: postings are appended, already varint-encoded, to per-term
   byte buffers until the memory budget (`--mem-mb`) is reached.
4. **Flush**: buffers are written as a sorted segment and memory is released.
5. **Merge**: segments are merged term by term with a priority queue into the
   final files.

## On-disk layout

```
terms.dict     sorted dictionary: df, total tf, offsets, max tf, min doc length
terms.idx      every 64th term, kept on heap (binary search, then scan <= 64 entries)
postings.doc   blocks of 128 (docDelta, freq) varints + a skip table per term
postings.pos   position deltas, separate so ranked queries never read them
doclens.bin    token length of every document (on heap: 4 bytes / doc)
docs.dat/.idx  stored title, URL and snippet text per document
```

Each skip-table entry records a block's last doc id, its offsets, and its max
term frequency and min document length. `advance(target)` binary-searches the
skip table and decodes only the block that can contain the target.

## Query processing

* **Ranked (MaxScore)**: query terms are ordered by their maximum possible
  score. Once the top-k heap is full, terms whose combined maximum cannot beat
  the k-th score become *non-essential*: they no longer generate candidates
  and are only probed with `advance()`. Results are exact.
* **Phrase**: postings are intersected rarest-first, and positions are
  decoded only for documents that contain every term.
* **Boolean**: `DocIdIterators.and / or / not` evaluate the query tree lazily.

`DiskSearchParityTest` checks that all three rankers, phrase and boolean
results match the in-memory engine exactly.

## Results (12-core laptop, warm page cache)

| Corpus | Docs | Tokens | Build | Index | BM25 p50 / p99 | Postings scored |
|---|---|---|---|---|---|---|
| Simple English Wikipedia | 286,035 | 38 M | 62 s | 245 MB | 0.10 / 1.0 ms | 30% |
| English Wikipedia, parts 1-4 | 216,875 | 286 M | 298 s | 851 MB | 0.16 / 1.6 ms | 16% |

Parallel decompression and analysis cut the Simple English build from 414 s
(single-threaded) to 62 s. Extrapolating from parts 1-4 (roughly 1/12 of the
tokens), **full English Wikipedia** should take about an hour and produce an
index of roughly 14 GB. Plan for the 25 GB dump plus about 25 GB free for the
index and temporary segments.

---

# Installation

## Requirements

Java 26+

Maven 3.9+

Docker (optional)

---

## Build

```
mvn clean package
```

This produces a self-contained runnable jar at `target/searchengine.jar`.

---

## Run Tests

```
mvn test
```

---

# Example Usage

Place text files in `data/raw/` (a small sample corpus ships with the project).

> **Quoting note.** The examples below use bash / cmd quoting
> (`-Dexec.args="..."`). In **PowerShell** the inner quotes are stripped, so
> quote the whole token instead: `mvn exec:java '-Dexec.args=--serve 8080'`.

## Command line

One-shot ranked search:

```
mvn exec:java -Dexec.args="inverted index ranking"
```

On the in-memory engine the default ranker is TF-IDF; on a disk index it is BM25.

Interactive REPL (no arguments):

```
mvn exec:java
```

REPL commands:

```
<text>            ranked search
"<phrase>"        phrase search (terms must be adjacent, in order)
:bool <expr>      boolean search using AND / OR / NOT and ( )
:ranker <name>    switch ranking algorithm (tfidf, cosine, bm25)
:rankers          list available rankers
:limit <n>        number of results to show (default 10)
:save <file>      persist an in-memory index to disk
:help             show help
:quit / :q        exit
```

Startup source flags (choose one, before any query):

```
--index <dir>     search an on-disk index built with --build-index
--load <file>     rebuild the index from a saved snapshot
--html <folder>   index a folder of .html/.htm files
--crawl <url> [n] crawl up to n pages (default 25) from a seed URL
--serve [port]    start the HTTP search API (default $PORT or 8080)
```

Example output:

```
Indexed 5 documents from data/raw.
 1. doc=1  score=5.2507  data\raw\doc2.txt
    An inverted index maps each term to the list of documents that contain it...
```

## Wikipedia-scale search (disk index)

Download a dump. The `-multistream` variant decompresses in parallel:

```
curl -LO https://dumps.wikimedia.org/simplewiki/latest/simplewiki-latest-pages-articles-multistream.xml.bz2
```

Build the index (repeat `--wiki`, or pass a directory such as the English
`enwiki-latest-pages-articles-multistreamN.xml-pApB.bz2` part files):

```
java -Xmx2g -jar target/searchengine.jar --build-index --out indexes/simplewiki \
     --wiki simplewiki-latest-pages-articles-multistream.xml.bz2
```

Options: `--max-docs n` (sample), `--mem-mb n` (postings buffer before a
segment is flushed, default 1/3 of the heap), `--threads n` (default: all
cores). `--folder <dir>` and `--html <dir>` build disk indexes from other
sources.

Search it from the CLI, the REPL, or the web UI:

```
java -jar target/searchengine.jar --index indexes/simplewiki black hole
java -jar target/searchengine.jar --index indexes/simplewiki
java -jar target/searchengine.jar --index indexes/simplewiki --serve 8080
```

Benchmark it:

```
java -jar target/searchengine.jar --bench --index indexes/simplewiki --queries 2000
```

## Docker

```
docker build -t searchengine .
docker run -p 8080:8080 searchengine                       # bundled sample corpus
docker run -p 8080:8080 -v "$PWD/indexes/simplewiki:/index:ro" searchengine --index /index --serve
```

The server reads `$PORT` when `--serve` has no port, handles requests on a
thread pool, and shuts down gracefully on SIGTERM.

## Web UI

Start the server and open the responsive single-page UI in a browser:

```
mvn exec:java -Dexec.args="--serve 8080"
```

In PowerShell, quote the whole token instead: `mvn exec:java '-Dexec.args=--serve 8080'`.

Then visit <http://localhost:8080/>. The UI works on both mobile and desktop
screens and supports ranked (TF-IDF / BM25 / Cosine), phrase, and boolean search
with a results list showing score, path, and snippet.

## REST API

The same server exposes JSON endpoints used by the UI:

```
GET /               responsive web UI
GET /search?q=<query>&ranker=tfidf|cosine|bm25&limit=N    (limit <= 100)
GET /phrase?q=<phrase>&limit=N
GET /bool?q=<expr with AND / OR / NOT>&limit=N
GET /health
```

Example:

```
curl "http://localhost:8080/search?q=ranking%20documents&ranker=bm25"

{"query":"ranking documents","mode":"bm25","count":3,"total":3,"tookMs":0.21,"results":[
  {"docId":2,"score":1.93,"title":"","path":"data/raw/doc3.txt","snippet":"TF-IDF weighs a term..."}
]}
```

`total` is omitted when MaxScore skipped non-competitive documents and so did
not count every match. Invalid input returns `400`; unexpected failures return
a JSON `500`.

---

# Development Roadmap

## STEP 1
Parser pipeline

✔ tokenizer  
✔ filters  
✔ document loader  

---

## STEP 2
Inverted index ✔

term → postings list

---

## STEP 3
ranking algorithms ✔

TF-IDF ✔
Cosine ✔
BM25 ✔

---

## STEP 4
query engine ✔

ranked search ✔
phrase search ✔
boolean search ✔
search API (CLI + REST) ✔

---

## STEP 5
disk storage ✔

serialize / reload index ✔

---

## STEP 6
optimization

faster lookup ✔ (positional index)
compression ✔ (gzip-compressed index)

---

## STEP 7
advanced features:

- phrase search ✔
- stemming ✔ (Porter)
- BM25 ranking ✔
- web crawler ✔
- incremental indexing ✔
- REST API ✔

---

## STEP 8
Wikipedia scale ✔

- streaming Wikipedia dump reader ✔
- SPIMI disk index with block postings and skip tables ✔
- memory-mapped reader ✔
- parallel build (bzip2 + analysis) ✔
- MaxScore top-k, disk phrase and boolean search ✔
- benchmark command ✔
- runnable jar, Dockerfile, CI ✔

---

# Possible Next Steps

* Block-Max WAND, using the per-block max tf / min doc length already stored
* SIMD-friendly block encodings (e.g. PForDelta) instead of varints
* Field weighting (title matches above body matches)
* Query-time spelling correction from the term dictionary
* Incremental updates to a disk index (new segments + periodic merge)
* A full English Wikipedia build on a machine with ~50 GB free disk
