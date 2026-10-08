package dev.rojae.llmgateway.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class AdminFiltersTest {
    @Test void inclusiveDatesBecomeHalfOpenInstantsInSelectedZone() {
        var values = new LinkedMultiValueMap<String,String>();
        values.add("from", "2026-01-02"); values.add("to", "2026-01-02"); values.add("zone", "Asia/Seoul");
        var filters = AdminFilters.parse(values);
        assertThat(filters.start()).isEqualTo(Instant.parse("2026-01-01T15:00:00Z"));
        assertThat(filters.end()).isEqualTo(Instant.parse("2026-01-02T15:00:00Z"));
        assertThat(filters.traffic()).isEqualTo("live");
        assertThat(filters.source()).isEqualTo("gateway");
        assertThat(filters.billingMode()).isNull();
    }

    @Test void invalidDatesRangeZonePaginationAndDuplicatesAreRejected() {
        for (String[] pair : new String[][]{{"from","garbage"},{"zone","+09:00"},{"limit","101"},
                {"limit","0"},{"offset","-1"},{"offset","100001"},{"traffic","invented"},{"outcome","injected"},{"source","injected"},{"billingMode","paid_invoice"}}) {
            var values = new LinkedMultiValueMap<String,String>(); values.add(pair[0],pair[1]);
            assertThatThrownBy(() -> AdminFilters.parse(values)).isInstanceOf(IllegalArgumentException.class);
        }
        var values = new LinkedMultiValueMap<String,String>(); values.add("from","2020-01-01"); values.add("to","2026-01-01");
        assertThatThrownBy(() -> AdminFilters.parse(values)).isInstanceOf(IllegalArgumentException.class);
        values.clear(); values.add("limit","1"); values.add("limit","2");
        assertThatThrownBy(() -> AdminFilters.parse(values)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void datesRequirePositiveFourDigitYearsAndValidCalendarDays() {
        for(String date:new String[]{"+10000-01-01","+999999999-12-31","0000-01-01","-0001-01-01","2026-02-30"}) {
            var values=new LinkedMultiValueMap<String,String>();values.add("from",date);values.add("to",date);
            assertThatThrownBy(()->AdminFilters.parse(values)).as(date).isInstanceOf(IllegalArgumentException.class);
        }
    }

}
