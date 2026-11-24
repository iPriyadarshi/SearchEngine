# Deploying sift

This guide puts the search engine on a public server with HTTPS, then uses
that server to build a large index. Everything runs in Docker; the only
other thing you need is a domain name.

```
internet ──▶ Caddy (HTTPS, :443) ──▶ search server (:8080) ──▶ indexes/current  (read-only, memory-mapped)
                                                               data/models      (semantic search model)
             tools container (on demand): --crawl-web, --fetch-cc, --build-index, --embed, --eval
```

## 1. Choose a server

Any Linux VM with Docker works. Sizing for the work described below:

| | Minimum | Comfortable |
|---|---|---|
| CPU | 4 vCPU | 8+ vCPU (embedding is CPU-bound) |
| Memory | 8 GB | 16–32 GB (the OS caches the index in RAM) |
| Disk | 100 GB SSD | 250 GB+ SSD |
| Network | — | unmetered, ideally 1 Gbit/s |

Bandwidth matters most: Common Crawl files are ~1 GB each and yield ~6,500
English pages, so 1M pages is about 160 GB of downloads.

## 2. Install and start

```bash
# On the server (Ubuntu/Debian): install Docker
curl -fsSL https://get.docker.com | sh

git clone https://github.com/iPriyadarshi/SearchEngine.git sift && cd sift
cp .env.example .env            # then set DOMAIN=your.domain
mkdir -p data indexes
sudo chown -R 10001:10001 data indexes   # the containers run as uid 10001
```

Point your domain's DNS `A`/`AAAA` record at the server and open ports 80 and
443. Caddy obtains the HTTPS certificate on first start.

## 3. Get data and build the first index

All jobs run in the `tools` container; paths are relative to the project
directory. Jobs are resumable: rerun the same command after an interruption.

```bash
docker compose build

# Domain popularity list (seeds for the crawler, and a ranking signal)
mkdir -p data/tranco && curl -L -o data/tranco/top-1m.csv.zip https://tranco-list.eu/top-1m.csv.zip \
  && unzip -o data/tranco/top-1m.csv.zip -d data/tranco

# Popular sites, politely (robots.txt, 1 request/s per site). ~2.5 pages per site.
docker compose run --rm tools --crawl-web --seeds data/tranco/top-1m.csv \
  --corpus data/corpus/crawl --top 100000 --sites-parallel 128 --pages-per-site 20

# Long tail from Common Crawl (pick the latest crawl id at https://index.commoncrawl.org/)
docker compose run --rm tools --fetch-cc --cc CC-MAIN-2026-47 --cc-files 160 \
  --corpus data/corpus/cc --parallel-files 6

# Optional: Wikipedia (one 25 GB dump, or its part files in a folder)
#   https://dumps.wikimedia.org/enwiki/latest/  (…pages-articles-multistream*.xml-p*.bz2)

# Build the index into a new, dated directory
docker compose run --rm tools --build-index --out indexes/web-$(date +%F) \
  --corpus data/corpus/crawl --corpus data/corpus/cc \
  --domain-ranks data/tranco/top-1m.csv
```

### Semantic search (optional)

```bash
mkdir -p data/models/bge-small-en-v1.5 && cd data/models/bge-small-en-v1.5
for f in onnx/model.onnx vocab.txt; do
  curl -L -o "$(basename $f)" "https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/main/$f"; done
cd ../../..
docker compose run --rm tools --embed --index indexes/web-$(date +%F) \
  --model data/models/bge-small-en-v1.5 --max-tokens 128
```

Embedding runs at roughly 10–30 pages per second per 8 vCPUs, so budget
hours for a million pages. Without embeddings the engine uses keyword ranking.

### Check quality before going live

```bash
docker compose run --rm tools --eval --index indexes/web-$(date +%F) --verbose --tune
```

If `--tune` finds better weights on the held-out half, update
`WebRanker.Weights.defaults()` and rebuild the image.

## 4. Go live, and update without downtime

The server reads `indexes/current`, a symbolic link to a built index:

```bash
ln -sfn web-2026-11-25 indexes/current     # relative link, inside indexes/
docker compose up -d --build
curl -s https://your.domain/health          # {"status":"ok","documents":...}
```

To publish a newer index later, build it into a new directory, switch the
link, and restart only the search service (a few seconds):

```bash
ln -sfn web-2026-12-15 indexes/current && docker compose restart search
```

Keep the previous index until the new one is checked; delete it afterwards.

## 5. Operating it

* **Privacy:** neither the search server nor Caddy (no `log` directive) records
  searches; keep it that way, as the About page promises.
* **Abuse:** each visitor may make `RATE_LIMIT` searches per second
  (burst `RATE_BURST`), set in `docker-compose.yml`; excess requests get HTTP 429.
* **Health:** `GET /health` returns 200 with the document count; point an
  uptime monitor at it.
* **Crawler etiquette:** the crawler identifies itself with this repository's
  URL. Answer removal requests (issues on the repository) and block sites that
  ask via `robots.txt` (they are respected automatically on the next crawl).
* **Backups:** `data/corpus` is the expensive part (downloads and crawls);
  indexes can always be rebuilt from it.
