package io.github.muthuishere.toolnexus.mcp2026;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every code block in docs/*.md is preceded by &lt;!-- from: &lt;path&gt; --&gt; and must appear verbatim in that file
 * (an example that ExamplesTest runs). Edit the example, then re-copy the block.
 */
class DocsTest {
    static final Pattern FROM = Pattern.compile("(?s)<!-- from: (\\S+) -->\n```[a-z]*\n(.*?)\n```");

    @Test
    void snippetsMatchExamples() throws Exception {
        List<Path> pages;
        try (Stream<Path> s = Files.list(Path.of("docs"))) {
            pages = s.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
        assertEquals(6, pages.size(), "the 6 docs pages");
        List<String> errors = new ArrayList<>();
        int total = 0;
        for (Path page : pages) {
            String b = Files.readString(page);
            if (!b.contains("Java port of the spike")) errors.add(page + ": missing the spike banner");
            Matcher m = FROM.matcher(b);
            while (m.find()) {
                total++;
                Path src = Path.of(m.group(1));
                if (!Files.exists(src)) errors.add(page + ": " + src + " does not exist");
                else if (!Files.readString(src).contains(m.group(2)))
                    errors.add(page + ": block from " + src + " no longer matches the example:\n" + m.group(2));
            }
        }
        System.out.println(total + " code blocks checked across " + pages.size() + " pages");
        assertTrue(total >= 16, "too few blocks: " + total);
        assertEquals(List.of(), errors);
    }
}
