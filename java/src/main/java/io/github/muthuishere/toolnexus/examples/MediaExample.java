package io.github.muthuishere.toolnexus.examples;

import io.github.muthuishere.toolnexus.ContentPart;
import io.github.muthuishere.toolnexus.Media;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * Media generation (SPEC.md §7G) against the shared a2e.ai preset — both faces: the typed verb
 * ({@code Media.image}) and the tool face ({@code Media.tools()}, invoked directly).
 *
 * <pre>A2E_API_KEY=... ./gradlew runMedia</pre>
 *
 * <p>Without {@code A2E_API_KEY} it prints a hint and exits 0, so hermetic runs never touch the
 * network. The key is read by the preset's {@code ${A2E_API_KEY}} header and is never printed.
 */
public final class MediaExample {

    public static void main(String[] args) {
        String key = System.getenv("A2E_API_KEY");
        if (key == null || key.isEmpty()) {
            System.out.println("set A2E_API_KEY to run the media example (skipping: no network call made)");
            return;
        }

        Media media = Media.create(
                Media.loadStyle(Examples.fixture("media-styles/a2e.json")),
                new Media.Options().onProgress(p -> System.out.println("  .." + p.verb() + " " + p.status())));

        // 1. Typed verb.
        Media.MediaResult img = media.image(
                new Media.ImageInput("a red fox in fresh snow, watercolor", 768, 768, null, null, null));
        System.out.println("image status: " + img.status());
        for (Media.File f : img.files()) {
            System.out.println("  file: " + f.type() + " " + f.mimeType() + " " + f.url());
        }

        // 2. Tool face — the same engine as plain Tools an LLM would call.
        List<Tool> tools = media.tools();
        for (Tool t : tools) System.out.println("tool: " + t.name());
        Tool speech = tools.stream().filter(t -> t.name().equals("generate_speech")).findFirst().orElseThrow();
        ToolResult res = speech.execute(Map.of("text", "Hello from toolnexus."), null);
        System.out.println("speech output: " + res.output() + " isError: " + res.isError());
        if (res.parts() != null) {
            for (ContentPart p : res.parts()) {
                System.out.println("  part: " + p.type() + " " + p.mimeType() + " " + p.url());
            }
        }
    }
}
