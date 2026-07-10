package com.searchengine;

import com.searchengine.api.Index;
import com.searchengine.api.Parser;
import com.searchengine.api.Ranker;
import com.searchengine.api.TokenFilter;
import com.searchengine.api.Tokenizer;
import com.searchengine.document.model.Document;
import com.searchengine.document.source.FolderDocumentSource;
import com.searchengine.engine.ResultFormatter;
import com.searchengine.engine.SearchEngine;
import com.searchengine.index.core.MemoryInvertedIndex;
import com.searchengine.parser.core.DefaultParser;
import com.searchengine.parser.filter.LengthFilter;
import com.searchengine.parser.filter.LowercaseFilter;
import com.searchengine.parser.filter.StopwordFilter;
import com.searchengine.parser.tokenizer.RegexTokenizer;
import com.searchengine.query.result.SearchResult;
import com.searchengine.ranking.core.CosineSimilarityRanker;
import com.searchengine.ranking.core.TFIDFRanker;

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

        SearchEngine engine = buildEngine(DEFAULT_FOLDER);

        Map<String, Ranker> rankers = buildRankers(engine.getIndex());

        System.out.println("Indexed " + engine.getDocumentCount()
                + " documents from " + DEFAULT_FOLDER + ".");

        if (args.length > 0) {

            String query = String.join(" ", args);

            runOnce(engine, rankers.get("tfidf"), query);

            return;
        }

        repl(engine, rankers);
    }

    private static SearchEngine buildEngine(String folderPath) throws Exception {

        FolderDocumentSource source = new FolderDocumentSource(folderPath);

        List<Document> documents = source.loadDocuments();

        Tokenizer tokenizer = new RegexTokenizer();

        List<TokenFilter> filters = List.of(
                new LowercaseFilter(),
                new StopwordFilter(),
                new LengthFilter(2));

        Parser parser = new DefaultParser(tokenizer, filters);

        Index index = new MemoryInvertedIndex();

        SearchEngine engine = new SearchEngine(parser, index);

        engine.index(documents);

        return engine;
    }

    private static Map<String, Ranker> buildRankers(Index index) {

        Map<String, Ranker> rankers = new LinkedHashMap<>();

        rankers.put("tfidf", new TFIDFRanker(index));

        rankers.put("cosine", new CosineSimilarityRanker(index));

        return rankers;
    }

    private static void runOnce(SearchEngine engine, Ranker ranker, String query) {

        SearchResult result = engine.search(query, ranker);

        System.out.println(new ResultFormatter(engine).format(result));
    }

    private static void repl(SearchEngine engine, Map<String, Ranker> rankers) throws Exception {

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
                  :ranker <name>  switch ranking algorithm (tfidf, cosine)
                  :rankers        list available rankers
                  :help           show this help
                  :quit / :q      exit""");
    }
}
