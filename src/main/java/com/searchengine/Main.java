package com.searchengine;

import com.searchengine.api.Index;
import com.searchengine.api.Parser;
import com.searchengine.api.Ranker;
import com.searchengine.api.TokenFilter;
import com.searchengine.api.Tokenizer;
import com.searchengine.api.DocumentSource;
import com.searchengine.crawler.JsoupPageFetcher;
import com.searchengine.crawler.WebCrawler;
import com.searchengine.document.model.Document;
import com.searchengine.document.source.FolderDocumentSource;
import com.searchengine.document.source.HtmlDocumentSource;
import com.searchengine.engine.ResultFormatter;
import com.searchengine.engine.SearchEngine;
import com.searchengine.http.SearchHttpServer;
import com.searchengine.index.core.PositionalInvertedIndex;
import com.searchengine.parser.core.DefaultParser;
import com.searchengine.parser.filter.LengthFilter;
import com.searchengine.parser.filter.LowercaseFilter;
import com.searchengine.parser.filter.PorterStemmerFilter;
import com.searchengine.parser.filter.StopwordFilter;
import com.searchengine.parser.tokenizer.RegexTokenizer;
import com.searchengine.query.executor.BooleanQueryExecutor;
import com.searchengine.query.executor.PhraseQueryExecutor;
import com.searchengine.query.model.PhraseQuery;
import com.searchengine.query.parser.BooleanQueryParser;
import com.searchengine.query.result.SearchResult;
import com.searchengine.ranking.core.BM25Ranker;
import com.searchengine.ranking.core.CosineSimilarityRanker;
import com.searchengine.ranking.core.TFIDFRanker;
import com.searchengine.storage.IndexStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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

    public static void main(String[] args) throws Exception {

        SearchEngine engine;

        // Source-selection flags consume their arguments; anything left over is
        // treated as a one-shot query.
        if (args.length >= 2 && args[0].equals("--load")) {

            engine = loadEngine(args[1]);

            System.out.println("Loaded " + engine.getDocumentCount()
                    + " documents from index " + args[1] + ".");

            args = java.util.Arrays.copyOfRange(args, 2, args.length);

        } else if (args.length >= 2 && args[0].equals("--html")) {

            engine = buildEngineFrom(new HtmlDocumentSource(args[1]));

            System.out.println("Indexed " + engine.getDocumentCount()
                    + " HTML documents from " + args[1] + ".");

            args = java.util.Arrays.copyOfRange(args, 2, args.length);

        } else if (args.length >= 2 && args[0].equals("--crawl")) {

            int maxPages = 25;

            int consumed = 2;

            if (args.length >= 3 && args[2].matches("\\d+")) {

                maxPages = Integer.parseInt(args[2]);

                consumed = 3;
            }

            engine = crawlEngine(args[1], maxPages);

            System.out.println("Crawled and indexed " + engine.getDocumentCount()
                    + " pages from " + args[1] + ".");

            args = java.util.Arrays.copyOfRange(args, consumed, args.length);

        } else {

            engine = buildEngine(DEFAULT_FOLDER);

            System.out.println("Indexed " + engine.getDocumentCount()
                    + " documents from " + DEFAULT_FOLDER + ".");
        }

        PositionalInvertedIndex index = (PositionalInvertedIndex) engine.getIndex();

        Map<String, Ranker> rankers = buildRankers(index);

        PhraseQueryExecutor phraseExecutor = new PhraseQueryExecutor(index);

        BooleanQueryExecutor booleanExecutor =
                new BooleanQueryExecutor(index, index, engine::analyzeQuery);

        if (args.length > 0 && args[0].equals("--serve")) {

            int port = args.length >= 2 && args[1].matches("\\d+") ? Integer.parseInt(args[1]) : 8080;

            SearchHttpServer httpServer =
                    new SearchHttpServer(port, engine, rankers, phraseExecutor, booleanExecutor);

            httpServer.start();

            System.out.println("Search API listening on http://localhost:" + httpServer.getPort());

            System.out.println("Try: /search?q=..., /phrase?q=..., /bool?q=..., /health");

            Thread.currentThread().join();

            return;
        }

        if (args.length > 0) {

            if (args[0].equals("--bool")) {

                String expr = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));

                runBoolean(engine, booleanExecutor, expr);

                return;
            }

            String query = String.join(" ", args);

            if (isPhrase(query)) {

                runPhrase(engine, phraseExecutor, query);

            } else {

                runOnce(engine, rankers.get("tfidf"), query);
            }

            return;
        }

        repl(engine, rankers, phraseExecutor, booleanExecutor);
    }

    private static Parser buildParser() {

        Tokenizer tokenizer = new RegexTokenizer();

        List<TokenFilter> filters = List.of(
                new LowercaseFilter(),
                new StopwordFilter(),
                new LengthFilter(2),
                new PorterStemmerFilter());

        return new DefaultParser(tokenizer, filters);
    }

    private static SearchEngine buildEngine(String folderPath) throws Exception {

        return buildEngineFrom(new FolderDocumentSource(folderPath));
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

        new IndexStore().load(engine, java.nio.file.Path.of(indexFile));

        return engine;
    }

    private static Map<String, Ranker> buildRankers(Index index) {

        Map<String, Ranker> rankers = new LinkedHashMap<>();

        rankers.put("tfidf", new TFIDFRanker(index));

        rankers.put("cosine", new CosineSimilarityRanker(index));

        rankers.put("bm25", new BM25Ranker(index));

        return rankers;
    }

    private static void runOnce(SearchEngine engine, Ranker ranker, String query) {

        SearchResult result = engine.search(query, ranker);

        System.out.println(new ResultFormatter(engine).format(result));
    }

    private static void runPhrase(SearchEngine engine, PhraseQueryExecutor executor, String quoted) {

        List<String> terms = engine.analyzeQuery(stripQuotes(quoted));

        SearchResult result = executor.execute(new PhraseQuery(terms));

        System.out.println(new ResultFormatter(engine).format(result));
    }

    private static void runBoolean(SearchEngine engine, BooleanQueryExecutor executor, String expr) {

        try {

            SearchResult result = executor.execute(new BooleanQueryParser().parse(expr));

            System.out.println(new ResultFormatter(engine).format(result));

        } catch (IllegalArgumentException e) {

            System.out.println("Invalid boolean query: " + e.getMessage());
        }
    }

    private static boolean isPhrase(String query) {

        String q = query.strip();

        return q.length() >= 2 && q.startsWith("\"") && q.endsWith("\"");
    }

    private static String stripQuotes(String query) {

        String q = query.strip();

        return q.substring(1, q.length() - 1);
    }

    private static void repl(SearchEngine engine, Map<String, Ranker> rankers,
                             PhraseQueryExecutor phraseExecutor,
                             BooleanQueryExecutor booleanExecutor) throws Exception {

        ResultFormatter formatter = new ResultFormatter(engine);

        String current = "tfidf";

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

                    System.out.println("Available rankers: " + String.join(", ", rankers.keySet()));

                    continue;
                }

                if (line.startsWith(":ranker ")) {

                    String name = line.substring(":ranker ".length()).strip();

                    if (rankers.containsKey(name)) {

                        current = name;

                        System.out.println("Ranker set to " + name + ".");

                    } else {

                        System.out.println("Unknown ranker: " + name);
                    }

                    continue;
                }

                if (line.startsWith(":bool ")) {

                    runBoolean(engine, booleanExecutor, line.substring(":bool ".length()));

                    continue;
                }

                if (line.startsWith(":save ")) {

                    String file = line.substring(":save ".length()).strip();

                    new IndexStore().save(engine, java.nio.file.Path.of(file));

                    System.out.println("Saved index to " + file + ".");

                    continue;
                }

                if (isPhrase(line)) {

                    List<String> terms = engine.analyzeQuery(stripQuotes(line));

                    SearchResult phraseResult = phraseExecutor.execute(new PhraseQuery(terms));

                    System.out.println(formatter.format(phraseResult));

                    continue;
                }

                SearchResult result = engine.search(line, rankers.get(current));

                System.out.println(formatter.format(result));
            }
        }

        System.out.println("Bye.");
    }

    private static void printHelp() {

        System.out.println("""
                Commands:
                  <text>          run a ranked search for <text>
                  "<phrase>"      phrase search (terms must be adjacent, in order)
                  :bool <expr>    boolean search using AND / OR / NOT and ( )
                  :ranker <name>  switch ranking algorithm (tfidf, cosine, bm25)
                  :rankers        list available rankers
                  :save <file>    persist the current index to disk
                  :help           show this help
                  :quit / :q      exit

                Startup source flags (choose one, before any query):
                  --load <file>     rebuild the index from a saved snapshot
                  --html <folder>   index a folder of .html/.htm files
                  --crawl <url> [n] crawl up to n pages (default 25) from a seed URL
                  --serve [port]    start the HTTP search API (default port 8080)""");
    }
}
