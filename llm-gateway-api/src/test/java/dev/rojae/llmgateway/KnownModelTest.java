package dev.rojae.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rojae.llmgateway.observe.KnownModel;
import dev.rojae.llmgateway.observe.Pricing;
import dev.rojae.llmgateway.observe.Usage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KnownModelTest {

    @Test
    @DisplayName("표에 올린 모델은 전부 실제로 비용이 계산된다 — 문서와 계산기가 어긋나면 안 된다")
    void everyListedModelIsActuallyPriced() {
        Usage usage = new Usage(1_000_000, 1_000_000, 2_000_000);

        for (KnownModel model : KnownModel.values()) {
            assertThat(Pricing.estimate(model.id(), usage))
                    .as("%s 는 문서에 단가가 적혀 있는데 비용이 계산되지 않는다", model.id())
                    .isNotNull()
                    .isEqualTo(model.inputPerMillion() + model.outputPerMillion());
        }
    }

    @Test
    @DisplayName("마크다운 표에 모든 모델이 한 줄씩 들어간다")
    void markdownTableCoversEveryModel() {
        String table = KnownModel.asMarkdownTable();

        for (KnownModel model : KnownModel.values()) {
            assertThat(table).contains("`" + model.id() + "`");
        }
        // 헤더 2줄(제목행 + 구분행) + 모델당 1줄
        assertThat(table.strip().lines().count()).isEqualTo(2 + KnownModel.values().length);
    }

    @Test
    @DisplayName("표는 개행으로 끝난다 — 뒤 문단이 표 안으로 빨려 들어가는 것을 막는다")
    void markdownTableEndsWithNewlineSoNextParagraphDetaches() {
        // 개행 없이 끝내면 Swagger UI 에서 다음 문단이 표의 마지막 행에 붙어 렌더링된다.
        assertThat(KnownModel.asMarkdownTable()).endsWith("\n");
    }
}
