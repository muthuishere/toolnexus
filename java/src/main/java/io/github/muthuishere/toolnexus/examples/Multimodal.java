package io.github.muthuishere.toolnexus.examples;

import io.github.muthuishere.toolnexus.ContentPart;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.NativeTool;
import io.github.muthuishere.toolnexus.Toolkit;
import io.github.muthuishere.toolnexus.ToolResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Live multimodal example: attach an image to a run, and let a tool hand one back.
 *
 * <p>Runs the shared 8x8 fixture ({@code examples/media/fixture.png}, quadrants red/green/blue/
 * white) through both provider styles against OpenRouter, and closes tasks 12.1-12.3 of the
 * {@code add-multimodal-content} change for this port.
 *
 * <pre>OPENROUTER_API_KEY=... ./gradlew runMultimodal --no-daemon</pre>
 *
 * <p><b>Arrival is proven by the prompt-token delta, never by the model's answer.</b> A model
 * asked to name colours will happily name four colours it never received — which is exactly how
 * the silent-drop bug this release fixes stayed hidden. It cannot fake prompt tokens.
 *
 * <p>Two things are measured per style:
 * <ol>
 *   <li>attachment — the identical request without and with {@link ContentPart#ofFile}; the
 *       prompt tokens must jump.</li>
 *   <li>§8A relocation — a tool returning an image on {@code ToolResult.parts}, called by the
 *       model. The run must complete AND cost materially more prompt tokens than the same run
 *       whose tool returns text only. On {@code openai} the image is relocated into a synthetic
 *       user message (a {@code tool} message cannot carry one); on {@code anthropic} it rides
 *       inside {@code tool_result}. Both are exercised here, live.</li>
 * </ol>
 *
 * <p><b>Builtins are off on every toolkit here.</b> Their ~2 000-token schema, plus any
 * turn-count difference it induces, drowns the very signal being measured.
 *
 * <p>Cheap by construction: tiny models, an 82-byte image, {@code max_tokens} capped at 40. The
 * API key is read from the environment and is never printed, logged or written.
 *
 * <p>Known upstream defect (see CHANGELOG.md): OpenRouter accepts an image bound for an Anthropic
 * model with HTTP 200 and drops it en route — a ~+4 token delta instead of hundreds. Observed on
 * both of its endpoints ({@code /chat/completions} with an openai-shaped {@code image_url}, and
 * the Anthropic-compatible {@code /v1/messages} with a native {@code source{}} block), and
 * reproducible with plain curl carrying no toolnexus code at all. It is a routing defect above
 * this library, so it is reported as {@code image=dropped-upstream}, never as a failure of
 * toolnexus. To exercise an Anthropic model with a working image path, point {@code baseUrl} at
 * {@code https://api.anthropic.com} with an {@code ANTHROPIC_API_KEY}.
 */
public final class Multimodal {

    private static final String ASK =
            "Name the four quadrant colours of this image, clockwise from top-left. "
                    + "Answer with four words only.";
    private static final String TOOL_ASK =
            "Call the screenshot tool, then name the four quadrant colours of the image it "
                    + "returns, clockwise from top-left. Answer with four words only.";
    private static final String[] COLOURS = {"red", "green", "blue", "white"};

    /**
     * Even this 82-byte image costs hundreds of prompt tokens wherever it actually arrives
     * (8 500 on gpt-4o-mini, 263 on gemini-2.5-flash-lite — a tile budget, not a byte count).
     * Anything under this is the image having been dropped en route; a double-digit difference is
     * turn-to-turn noise, not an image.
     */
    private static final int MIN_IMAGE_TOKENS = 200;

    private static final String[][] STYLES = {
            {"openai", "openai/gpt-4o-mini"},
            {"anthropic", "anthropic/claude-haiku-4.5"},
    };

    public static void main(String[] args) throws Exception {
        String key = System.getenv("OPENROUTER_API_KEY");
        if (key == null || key.isEmpty()) {
            System.out.println("(no OPENROUTER_API_KEY — skipping the live multimodal run)");
            return;
        }
        Path fixture = Path.of(Examples.fixture("media/fixture.png"));
        String openaiModel = System.getenv("OPENROUTER_MODEL");

        List<String> lines = new ArrayList<>();
        for (String[] s : STYLES) {
            String model = "openai".equals(s[0]) && openaiModel != null && !openaiModel.isEmpty()
                    ? openaiModel : s[1];
            lines.add(runStyle(s[0], model, key, fixture));
        }
        System.out.println();
        for (String line : lines) System.out.println(line);
    }

    private static String runStyle(String style, String model, String key, Path fixture)
            throws Exception {
        LlmClient agent = LlmClient.create(new LlmClient.Options()
                .baseUrl("https://openrouter.ai/api/v1")
                .style(style)
                .model(model)
                .apiKey(key)
                .requestParams(Map.of("max_tokens", 40)));

        // --- 1. attachment: the same request, without and with the image ---------
        // builtins off: their ~2 000-token schema would drown the signal we measure
        Toolkit bare = Toolkit.create(new Toolkit.Options().builtins(false));
        LlmClient.RunResult textOnly = agent.run(ASK, bare);
        LlmClient.RunResult withImage = agent.run(
                List.of(ContentPart.text(ASK), ContentPart.ofFile(fixture)), bare);
        bare.close();

        long ptokText = textOnly.usage.promptTokens;
        long ptokImage = withImage.usage.promptTokens;
        long delta = ptokImage - ptokText;
        boolean arrived = delta >= MIN_IMAGE_TOKENS;
        String answer = withImage.text == null ? "" : withImage.text;
        int colours = coloursNamed(answer);
        System.out.println("\n[" + style + "] " + model);
        System.out.println("  text-only ptok=" + ptokText + "  with-image ptok=" + ptokImage
                + "  delta=" + (delta >= 0 ? "+" : "") + delta);
        System.out.println("  answer: " + oneLine(answer) + "  (" + colours + "/4 colours named"
                + (arrived ? ")" : ", against an image it never received)"));
        if (!arrived) {
            System.out.println("  ^ image did NOT arrive: too few prompt tokens. Upstream drop, not a");
            System.out.println("    toolnexus failure — the block is emitted per SPEC §8A either way.");
        }

        // --- 2. §8A relocation: a tool that returns an image ---------------------
        Toolkit tkImg = Toolkit.create(new Toolkit.Options().builtins(false))
                .register(screenshotTool(fixture, true));
        Toolkit tkTxt = Toolkit.create(new Toolkit.Options().builtins(false))
                .register(screenshotTool(fixture, false));
        LlmClient.RunResult resImg = agent.run(TOOL_ASK, tkImg);
        LlmClient.RunResult resTxt = agent.run(TOOL_ASK, tkTxt);
        tkImg.close();
        tkTxt.close();

        List<String> called = new ArrayList<>();
        for (LlmClient.ToolCall c : resImg.toolCalls) called.add(c.name);
        long relocDelta = resImg.usage.promptTokens - resTxt.usage.promptTokens;
        // Two independent facts: the loop completed with the part in it (ours), and the
        // image actually reached the model (upstream's to drop).
        String reloc = resImg.text != null && !resImg.text.isEmpty() && called.contains("screenshot")
                ? "ok" : "failed";
        String relocImage = relocDelta >= MIN_IMAGE_TOKENS ? "ok" : "dropped-upstream";
        System.out.println("  tool calls: " + called + "  turns=" + resImg.turns + "/" + resTxt.turns
                + "  tool-result ptok delta=" + (relocDelta >= 0 ? "+" : "") + relocDelta
                + "  -> loop=" + reloc + " image=" + relocImage);
        System.out.println("  answer: " + oneLine(resImg.text == null ? "" : resImg.text));

        return "RESULT java style=" + style + " model=" + model
                + " ptok_text=" + ptokText + " ptok_image=" + ptokImage
                + " delta=" + (delta >= 0 ? "+" : "") + delta
                + " image=" + (arrived ? "ok" : "dropped-upstream")
                + " colours=" + colours + "/4"
                + " relocation=" + reloc + " reloc_image=" + relocImage
                + " reloc_delta=" + (relocDelta >= 0 ? "+" : "") + relocDelta;
    }

    /**
     * A tool returning an 8x8 screenshot — with or without the image part.
     *
     * <p>The parts-less twin is the control: the prompt-token difference between the two runs is
     * the image, and nothing else.
     */
    private static NativeTool screenshotTool(Path fixture, boolean withImage) {
        return NativeTool.of("screenshot",
                "Capture the current screen and return it as a PNG.",
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                args -> withImage
                        ? ToolResult.ok("screenshot captured, 8x8 png",
                                List.of(ContentPart.ofFile(fixture)))
                        : ToolResult.ok("screenshot captured, 8x8 png"));
    }

    private static int coloursNamed(String text) {
        String low = text == null ? "" : text.toLowerCase();
        int n = 0;
        for (String c : COLOURS) if (low.contains(c)) n++;
        return n;
    }

    private static String oneLine(String s) {
        String t = s.trim().replace('\n', ' ');
        return "'" + (t.length() > 120 ? t.substring(0, 120) : t) + "'";
    }

    private Multimodal() {}
}
