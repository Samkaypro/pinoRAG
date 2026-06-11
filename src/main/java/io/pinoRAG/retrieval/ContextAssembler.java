package io.pinoRAG.retrieval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// Greedy packs chunks into a token budget, highest score first. Token
// estimation is chars/4 which is close enough for the common English mix
// without pulling in a tokenizer dep.
@Component
public class ContextAssembler {

    private static final int CHARS_PER_TOKEN = 4;

    public Result assemble(List<ScoredChunk> ranked, int tokenBudget) {
        List<ScoredChunk> included = new ArrayList<>();
        StringBuilder rendered = new StringBuilder();
        int used = 0;
        for (ScoredChunk chunk : ranked) {
            String block = renderBlock(chunk);
            int tokens = estimateTokens(block);
            if (used + tokens > tokenBudget) {
                break;
            }
            rendered.append(block);
            included.add(chunk);
            used += tokens;
        }
        return new Result(rendered.toString(), included, used);
    }

    public int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / CHARS_PER_TOKEN);
    }

    static String renderBlock(ScoredChunk chunk) {
        return "[chunk-" + chunk.chunkId() + "] " + chunk.body() + "\n\n";
    }

    public record Result(String context, List<ScoredChunk> includedChunks, int approxTokens) {
    }
}
