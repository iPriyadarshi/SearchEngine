# Search Engine 

A **modular, extensible mini search engine** built from scratch in Java to understand the fundamentals of **Information Retrieval (IR)** systems.

This project implements:

- document parsing pipeline (tokenizer + filters + Porter stemmer)
- inverted index and positional inverted index
- ranking algorithms (TF-IDF, Cosine, BM25)
- query processing (ranked, phrase, and boolean queries)
- disk persistence of the index
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
├── document/       Document model and sources (folder, HTML)
├── parser/         tokenizer, filters, and the Porter stemmer
├── index/          memory and positional inverted indexes + posting models
├── ranking/        TF-IDF, Cosine, and BM25 rankers
├── query/          query models, parsers, and executors (ranked, phrase, boolean)
├── engine/         SearchEngine facade + result formatting
├── storage/        disk persistence (IndexStore)
├── crawler/        web crawler + pluggable page fetcher
├── http/           REST search API + responsive web UI over the JDK HttpServer
└── Main            command line entry point

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

## Factory Pattern (future)

Configuration-based component creation.

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

# Installation

## Requirements

Java 26+

Maven 3.9+

---

## Build

```
mvn clean install
```

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
:save <file>      persist the current index to disk
:help             show help
:quit / :q        exit
```

Startup source flags (choose one, before any query):

```
--load <file>     rebuild the index from a saved snapshot
--html <folder>   index a folder of .html/.htm files
--crawl <url> [n] crawl up to n pages (default 25) from a seed URL
--serve [port]    start the HTTP search API (default port 8080)
```

Example output:

```
Indexed 5 documents from data/raw.
 1. doc=1  score=5.2507  data\raw\doc2.txt
    An inverted index maps each term to the list of documents that contain it...
```

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
GET /search?q=<query>&ranker=tfidf|cosine|bm25&limit=N
GET /phrase?q=<phrase>&limit=N
GET /bool?q=<expr with AND / OR / NOT>
GET /health
```

Example:

```
curl "http://localhost:8080/search?q=ranking%20documents&ranker=bm25"

{"query":"ranking documents","mode":"bm25","count":3,"results":[
  {"docId":2,"score":1.93,"path":"data/raw/doc3.txt","snippet":"TF-IDF weighs a term..."}
]}
```

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

# Example Extensions

Possible improvements:

* Porter Stemmer
* BM25 ranking
* boolean queries
* phrase search
* positional index
* index compression
* web crawler integration
* incremental indexing
* REST API interface

---
