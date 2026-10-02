package space.nextpass.gcat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import space.nextpass.gcat.GcatDate.Precision;

class GcatDateTest {

    // Every shape found in the LDate, SDate, DDate and ODate columns on 2 October 2026.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2026 Jul 11 0402:25 | 2026-07-11T04:02:25Z | SECOND  | false",
            "1957 Oct  4 1933    | 1957-10-04T19:33:00Z | MINUTE  | false",
            "2026 Jun 19 2200?   | 2026-06-19T22:00:00Z | MINUTE  | true",
            "2026 Mar  7 0402:25? | 2026-03-07T04:02:25Z | SECOND | true",
            "2026 Oct  2         | 2026-10-02T00:00:00Z | DAY     | false",
            "2026 Mar 9          | 2026-03-09T00:00:00Z | DAY     | false",
            "2026 Jul 20?        | 2026-07-20T00:00:00Z | DAY     | true",
            "2026 Jul            | 2026-07-01T00:00:00Z | MONTH   | false",
            "2026 May?           | 2026-05-01T00:00:00Z | MONTH   | true",
            "2026 Q3?            | 2026-07-01T00:00:00Z | QUARTER | true",
            "1995                | 1995-01-01T00:00:00Z | YEAR    | false",
            "1995?               | 1995-01-01T00:00:00Z | YEAR    | true",
            "1960s?              | 1960-01-01T00:00:00Z | DECADE  | true",
    })
    void keepsWhatGcatKnowsAndNoMore(String text, String start, Precision precision, boolean uncertain) {
        assertThat(GcatDate.parse(text))
                .isEqualTo(new GcatDate(Instant.parse(start), precision, uncertain));
    }

    @Test
    void noneIsNull() {
        assertThat(GcatDate.parse("-")).isNull();
        assertThat(GcatDate.parse("  -  ")).isNull();
        assertThat(GcatDate.parse("")).isNull();
        assertThat(GcatDate.parse(null)).isNull();
    }

    @Test
    void anUnknownShapeIsAnErrorNotAGuess() {
        assertThatThrownBy(() -> GcatDate.parse("2026 Foo 3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GcatDate.parse("2026 Feb 30")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GcatDate.parse("early 2026")).isInstanceOf(IllegalArgumentException.class);
    }
}
