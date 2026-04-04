# Search Engine 

A **modular, extensible mini search engine** built from scratch in Java to understand the fundamentals of **Information Retrieval (IR)** systems.

This project implements:

- document parsing pipeline
- inverted index
- ranking algorithms (TF, TF-IDF)
- query processing
- memory vs disk tradeoffs
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
* frequency-based ranking
* TF scoring
* TF-IDF scoring

### Query Engine
* keyword queries
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
├── TfRanker
├── TfIdfRanker
└── BM25Ranker (future)
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

Java 17+

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

Place text files in:

```
data/
```

Example:

```
doc1.txt
doc2.txt
doc3.txt
```

Run:

```
Main.java
```

Search query:

```
search engine
```

Output:

```
doc1 score=0.87
doc3 score=0.52
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
Inverted index

term → postings list

---

## STEP 3
ranking algorithms

TF
TF-IDF

---

## STEP 4
query engine

search API

---

## STEP 5
disk storage

serialize index

---

## STEP 6
optimization

compression
faster lookup

---

## STEP 7
advanced features:

- phrase search
- stemming
- BM25 ranking
- web crawler
- incremental indexing

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
