package com.searchengine;

import com.searchengine.api.Parser;
import com.searchengine.api.TokenFilter;
import com.searchengine.api.Tokenizer;
import com.searchengine.document.model.Document;
import com.searchengine.document.source.FolderDocumentSource;
import com.searchengine.parser.core.DefaultParser;
import com.searchengine.parser.filter.LengthFilter;
import com.searchengine.parser.filter.LowercaseFilter;
import com.searchengine.parser.filter.StopwordFilter;
import com.searchengine.parser.tokenizer.RegexTokenizer;

import java.util.List;

public class Main {

    public static void main(String[] args) throws Exception {

        String folderPath = "data/raw";

        FolderDocumentSource source = new FolderDocumentSource(folderPath);

        List<Document> documents = source.loadDocuments();

        Tokenizer tokenizer = new RegexTokenizer();

        List<TokenFilter> filters = List.of(

                new LowercaseFilter(),

                new StopwordFilter(),

                new LengthFilter(2));

        Parser parser = new DefaultParser(tokenizer, filters);

        for (Document doc : documents) {

            List<String> tokens = parser.parse(doc);

            System.out.println("DOC " + doc.getId());

            System.out.println(tokens);

            System.out.println();
        }
    }
}