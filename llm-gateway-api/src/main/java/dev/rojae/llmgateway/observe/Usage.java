package dev.rojae.llmgateway.observe;

/**
 * 업스트림이 알려준 토큰 사용량.
 *
 * <p>캐시 토큰을 따로 갖는 이유: Anthropic 은 캐시 쓰기/읽기를 <b>다른 단가</b>로 청구한다.
 * 한 덩어리로 합치면 Claude Code 처럼 prompt caching 을 상시 쓰는 클라이언트의 비용이
 * 크게 어긋난다 (캐시 읽기는 기본 입력의 0.1 배다).
 *
 * <p>{@code promptTokens} 는 캐시를 제외한 일반 입력으로 정규화한다.
 * <ul>
 *   <li>OpenAI: 전체 입력에서 {@code cached_tokens} 를 뺀 값</li>
 *   <li>Anthropic: {@code input_tokens} — <b>캐시분을 제외한</b> 입력. 캐시분은 아래 두 필드로 온다</li>
 * </ul>
 */
public record Usage(int promptTokens,
                    int completionTokens,
                    int totalTokens,
                    int cacheWriteTokens,
                    int cacheWrite1hTokens,
                    int cacheReadTokens) {

    public static final Usage EMPTY = new Usage(0, 0, 0, 0, 0, 0);

    /** 캐시가 없는(또는 캐시를 구분하지 않는) 업스트림용. */
    public Usage(int promptTokens, int completionTokens, int totalTokens) {
        this(promptTokens, completionTokens, totalTokens, 0, 0, 0);
    }

    /** 5분 TTL 캐시 쓰기만 있는 경우. */
    public Usage(int promptTokens, int completionTokens, int totalTokens,
                 int cacheWriteTokens, int cacheReadTokens) {
        this(promptTokens, completionTokens, totalTokens, cacheWriteTokens, 0, cacheReadTokens);
    }

    /** 청구 대상 입력 토큰 전체. 단가가 달라서 비용 계산에는 쓰지 말고 표시용으로만 쓴다. */
    public int billedInputTokens() {
        return promptTokens + cacheWriteTokens + cacheWrite1hTokens + cacheReadTokens;
    }
}
