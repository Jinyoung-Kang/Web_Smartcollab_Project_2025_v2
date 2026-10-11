package com.smartcollab.ai;

import com.anthropic.client.AnthropicClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Claude(Anthropic API)로 문서를 요약·번역합니다 [AI-01].
 */
@Component
public class ClaudeDocumentAi implements DocumentAi {

    private final AiProperties props;

    @Autowired
    public ClaudeDocumentAi(AiProperties props) {
        this(props, null);
    }

    ClaudeDocumentAi(AiProperties props, AnthropicClient client) {
        this.props = props;
    }

    @Override
    public boolean enabled() {
        return props.enabled();
    }

    @Override
    public String model() {
        return props.model();
    }

    @Override
    public long countSummaryTokens(AiDocument document) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public long countTranslationTokens(AiDocument document, TargetLanguage target) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Output<Summary> summarize(AiDocument document) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Output<String> translate(AiDocument document, TargetLanguage target) {
        throw new UnsupportedOperationException("not implemented");
    }
}
