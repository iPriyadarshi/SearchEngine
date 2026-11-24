package com.searchengine;

import com.searchengine.api.DocumentSource;
import com.searchengine.api.Index;
import com.searchengine.api.Parser;
import com.searchengine.api.Ranker;
import com.searchengine.api.TokenFilter;
import com.searchengine.api.Tokenizer;
import com.searchengine.crawler.JsoupPageFetcher;
import com.searchengine.crawler.WebCrawler;
import com.searchengine.document.source.FolderDocumentSource;
import com.searchengine.document.source.HtmlDocumentSource;
import com.searchengine.engine.SearchEngine;
import com.searchengine.http.SearchHttpServer;
import com.searchengine.index.core.PositionalInvertedIndex;
import com.searchengine.index.disk.DiskIndex;
import com.searchengine.parser.core.DefaultParser;
import com.searchengine.parser.filter.LengthFilter;
import com.searchengine.parser.filter.LowercaseFilter;
import com.searchengine.parser.filter.PorterStemmerFilter;
import com.searchengine.parser.filter.StopwordFilter;
import com.searchengine.parser.tokenizer.RegexTokenizer;
import com.searchengine.query.executor.BooleanQueryExecutor;
import com.searchengine.query.executor.PhraseQueryExecutor;
import com.searchengine.ranking.core.BM25Ranker;
import com.searchengine.ranking.core.CosineSimilarityRanker;
import com.searchengine.ranking.core.TFIDFRanker;
import com.searchengine.search.DiskSearchBackend;
import com.searchengine.search.MemorySearchBackend;
import com.searchengine.search.SearchBackend;
import com.searchengine.search.SearchBackend.Hit;
import com.searchengine.search.SearchBackend.Hits;
import com.searchengine.storage.IndexStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command line entry point.
 * <p>
 * With arguments: runs the joined arguments as a one-shot query and prints
 * ranked results, then exits.
 * <p>
 * Without arguments: starts an interactive REPL. Type a query to search, or use
 * {@code :help} to list commands.
 */
public class Main {

    private static final String DEFAULT_FOLDER = "data/raw";

    private static final int DEFAULT_PORT = 8080;

    private static final int DEFAULT_LIMIT = 10;

    public static void main(String[] args) throws Exception {

        if (args.length > 0 && args[0].equals("--build-index")) {

            BuildIndexCommand.run(Arrays.copyOfRange(args, 1, args.length));

            return;
        }

        SearchBackend backend;

        // Source-selection flags consume their arguments; anything left over is
        // treated as a one-shot query.
        if (args.length >= 2 && args[0].equals("--index")) {

            Path dir = Path.of(args[1]);

            backend = new DiskSearchBackend(DiskIndex.open(dir), buildParser(), dir);

            args = Arrays.copyOfRange(args, 2, args.length);

        } else if (args.length >= 2 && args[0].equals("--load")) {

            backend = memoryBackend(loadEngine(args[1]), "snapshot " + args[1]);

            args = Arrays.copyOfRange(args, 2, args.length);

        } else if (args.length >= 2 && args[0].equals("--html")) {

            backend = memoryBackend(buildEngineFrom(new HtmlDocumentSource(args[1])), args[1]);

            args = Arrays.copyOfRange(args, 2, args.length);

        } else if (args.length >= 2 && args[0].equals("--crawl")) {

            int maxPages = 25;

            int consumed = 2;

            if (args.length >= 3 && args[2].matches("\\d+")) {

                maxPages = Integer.parseInt(args[2]);

                consumed = 3;
            }

            backend = memoryBackend(crawlEngine(args[1], maxPages), "crawl of " + args[1]);

            args = Arrays.copyOfRange(args, consumed, args.length);

        } else {

            backend = memoryBackend(buildEngineFrom(new FolderDocumentSource(DEFAULT_FOLDER)), DEFAULT_FOLDER);
        }

        System.out.println("Searching " + backend.describe() + ".");

        try (backend) {

            if (args.length > 0 && args[0].equals("--serve")) {

                serve(backend, args.length >= 2 && args[1].matches("\\d+") ? Integer.parseInt(args[1]) : defaultPort());

                return;
            }

            if (args.length > 0) {

                runOnce(backend, args);

                return;
            }

            repl(backend);
        }
    }

    /**
     * The default analysis chain, shared by every index this program builds.
     */
    public static Parser buildParser() {

        Tokenizer tokenizer = new RegexTokenizer();

        List<TokenFilter> filters = List.of(
                new LowercaseFilter(),
                new StopwordFilter(),
                new LengthFilter(2),
                new PorterStemmerFilter());

        return new DefaultParser(tokenizer, filters);
    }

    private static int defaultPort() {

        String port = System.getenv("PORT");

        return port != null && port.matches("\\d+") ? Integer.parseInt(port) : DEFAULT_PORT;
    }

    private static void serve(SearchBackend backend, int port) throws Exception {

        SearchHttpServer httpServer = new SearchHttpServer(port, backend);

        httpServer.start();

        // Let in-flight requests finish on Ctrl+C / SIGTERM before the index is unmapped.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {

            httpServer.stop(2);

            backend.close();
        }));

        System.out.println("Web UI + API at http://localhost:" + httpServer.getPort() + "/");

        System.out.println("API: /search?q=..., /phrase?q=..., /bool?q=..., /health");

        Thread.currentThread().join();
    }

    private static MemorySearchBackend memoryBackend(SearchEngine engine, String source) {

        PositionalInvertedIndex index = (PositionalInvertedIndex) engine.getIndex();

        return new MemorySearchBackend(engine, buildRankers(index), new PhraseQueryExecutor(index),
                new BooleanQueryExecutor(index, index, engine::analyzeQuery), source);
    }

    private static SearchEngine buildEngineFrom(DocumentSource source) throws Exception {

        SearchEngine engine = new SearchEngine(buildParser(), new PositionalInvertedIndex());

        engine.index(source.loadDocuments());

        return engine;
    }

    private static SearchEngine crawlEngine(String seedUrl, int maxPages) {

        WebCrawler crawler = new WebCrawler(new JsoupPageFetcher(), maxPages);

        SearchEngine engine = new SearchEngine(buildParser(), new PositionalInvertedIndex());

        engine.index(crawler.crawl(seedUrl));

        return engine;
    }

    private static SearchEngine loadEngine(String indexFile) throws Exception {

        SearchEngine engine = new SearchEngine(buildParser(), new PositionalInvertedIndex());

        new IndexStore().load(engine, Path.of(indexFile));

        return engine;
    }

    private static Map<String, Ranker> buildRankers(Index index) {

        Map<String, Ranker> rankers = new LinkedHashMap<>();

        rankers.put("tfidf", new TFIDFRanker(index));

        rankers.put("cosine", new CosineSimilarityRanker(index));

        rankers.put("bm25", new BM25Ranker(index));

        return rankers;
    }

    private static void runOnce(SearchBackend backend, String[] args) {

        if (args[0].equals("--bool")) {

            runBoolean(backend, String.join(" ", Arrays.copyOfRange(args, 1, args.length)), DEFAULT_LIMIT);

            return;
        }

        String query = String.join(" ", args);

        if (isPhrase(query)) {

            print(backend.phrase(stripQuotes(query), DEFAULT_LIMIT));

        } else {

            print(backend.search(query, backend.defaultRanker(), DEFAULT_LIMIT));
        }
    }

    private static void runBoolean(SearchBackend backend, String expr, int limit) {

        try {

            print(backend.bool(expr, limit));

        } catch (IllegalArgumentException e) {

            System.out.println("Invalid boolean query: " + e.getMessage());
        }
    }

    static String format(Hits result) {

        if (result.hits().isEmpty()) {

            return "No matching documents.";
        }

        StringBuilder sb = new StringBuilder();

        int rank = 1;

        for (Hit hit : result.hits()) {

            String label = hit.title().isEmpty() ? hit.path() : hit.title() + "  " + hit.path();

            sb.append(String.format("%2d. doc=%d  score=%.4f  %s%n", rank++, hit.docId(), hit.score(), label));

            if (!hit.snippet().isEmpty()) {

                sb.append("    ").append(hit.snippet()).append(System.lineSeparator());
            }
        }

        String total = result.total() >= 0 ? String.format("%,d", result.total()) : "top";

        sb.append(String.format("(%d of %s, %.1f ms)", result.hits().size(), total, result.tookMillis()));

        return sb.toString();
    }

    private static void print(Hits result) {

        System.out.println(format(result));
    }

    private static boolean isPhrase(String query) {

        String q = query.strip();

        return q.length() >= 2 && q.startsWith("\"") && q.endsWith("\"");
    }

    private static String stripQuotes(String query) {

        String q = query.strip();

        return q.substring(1, q.length() - 1);
    }

    private static void repl(SearchBackend backend) throws Exception {

        String current = backend.defaultRanker();

        int limit = DEFAULT_LIMIT;

        System.out.println("Interactive search. Type :help for commands, :quit to exit.");

        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {

            while (true) {

                System.out.print("\nsearch(" + current + ")> ");

                String line = in.readLine();

                if (line == null) {

                    break;
                }

                line = line.strip();

                if (line.isEmpty()) {

                    continue;
                }

                if (line.equals(":quit") || line.equals(":q")) {

                    break;
                }

                if (line.equals(":help")) {

                    printHelp();

                    continue;
                }

                if (line.equals(":rankers")) {

                    System.out.println("Available rankers: " + String.join(", ", backend.rankers()));

                    continue;
                }

                if (line.startsWith(":ranker ")) {

                    String name = line.substring(":ranker ".length()).strip();

                    if (backend.rankers().contains(name)) {

                        current = name;

                        System.out.println("Ranker set to " + name + ".");

                    } else {

                        System.out.println("Unknown ranker: " + name);
                    }

                    continue;
                }

                if (line.startsWith(":limit ")) {

                    String value = line.substring(":limit ".length()).strip();

                    if (value.matches("\\d+") && Integer.parseInt(value) > 0) {

                        limit = Integer.parseInt(value);

                        System.out.println("Showing up to " + limit + " results.");

                    } else {

                        System.out.println("Limit must be a positive number.");
                    }

                    continue;
                }

                if (line.startsWith(":bool ")) {

                    runBoolean(backend, line.substring(":bool ".length()), limit);

                    continue;
                }

                if (line.startsWith(":save ")) {

                    save(backend, line.substring(":save ".length()).strip());

                    continue;
                }

                if (isPhrase(line)) {

                    print(backend.phrase(stripQuotes(line), limit));

                    continue;
                }

                print(backend.search(line, current, limit));
            }
        }

        System.out.println("Bye.");
    }

    private static void save(SearchBackend backend, String file) throws Exception {

        if (!(backend instanceof MemorySearchBackend memory)) {

            System.out.println(":save applies to in-memory indexes; a disk index is already persisted.");

            return;
        }

        Path path = Path.of(file);

        if (file.endsWith(".gz")) {

            new IndexStore().saveCompressed(memory.engine(), path);

        } else {

            new IndexStore().save(memory.engine(), path);
        }

        System.out.println("Saved index to " + file + ".");
    }

    private static void printHelp() {

        System.out.println("""
                Commands:
                  <text>          run a ranked search for <text>
                  "<phrase>"      phrase search (terms must be adjacent, in order)
                  :bool <expr>    boolean search using AND / OR / NOT and ( )
                  :ranker <name>  switch ranking algorithm (tfidf, cosine, bm25)
                  :rankers        list available rankers
                  :limit <n>      number of results to show (default 10)
                  :save <file>    persist an in-memory index to disk (.gz = compressed)
                  :help           show this help
                  :quit / :q      exit

                Startup source flags (choose one, before any query):
                  --index <dir>     search an on-disk index built with --build-index
                  --load <file>     rebuild the index from a saved snapshot
                  --html <folder>   index a folder of .html/.htm files
                  --crawl <url> [n] crawl up to n pages (default 25) from a seed URL
                  --serve [port]    start the HTTP search API (default $PORT or 8080)

                Build an on-disk index (for corpora larger than memory):
                  --build-index --out <dir> (--wiki <dump> | --folder <dir> | --html <dir>)
                                [--max-docs n] [--mem-mb n] [--threads n]""");
    }
}
