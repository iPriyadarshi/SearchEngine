package com.searchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BuildIndexCommandTest {

    @Test
    void dumpDirectoriesAreReadInPartOrder(@TempDir Path dir) throws Exception {

        List<String> names = List.of(
                "enwiki-latest-pages-articles-multistream10.xml-p4045403p5399366.bz2",
                "enwiki-latest-pages-articles-multistream2.xml-p41243p151573.bz2",
                "enwiki-latest-pages-articles-multistream11.xml-p6899367p7054859.bz2",
                "enwiki-latest-pages-articles-multistream11.xml-p5399367p6899366.bz2",
                "enwiki-latest-pages-articles-multistream1.xml-p1p41242.bz2",
                "notes.txt");

        for (String name : names) {

            Files.createFile(dir.resolve(name));
        }

        List<String> ordered = BuildIndexCommand.dumpFiles(dir).stream()
                .map(p -> p.getFileName().toString())
                .toList();

        assertEquals(List.of(
                "enwiki-latest-pages-articles-multistream1.xml-p1p41242.bz2",
                "enwiki-latest-pages-articles-multistream2.xml-p41243p151573.bz2",
                "enwiki-latest-pages-articles-multistream10.xml-p4045403p5399366.bz2",
                "enwiki-latest-pages-articles-multistream11.xml-p5399367p6899366.bz2",
                "enwiki-latest-pages-articles-multistream11.xml-p6899367p7054859.bz2"), ordered);
    }

    @Test
    void aSingleFileIsUsedAsIs(@TempDir Path dir) throws Exception {

        Path dump = Files.createFile(dir.resolve("simplewiki.xml.bz2"));

        assertEquals(List.of(dump), BuildIndexCommand.dumpFiles(dump));
    }
}
