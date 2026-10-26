package io.github.emanuelluis.vaccine.exports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvWriterTest {

    @Test
    void bomSemicolonDecimalCommaAndEmptyNulls() {
        byte[] out = CsvWriter.write(
                List.of("code", "value", "numerator", "suppressed"),
                List.of(
                        Arrays.asList("2927408", new BigDecimal("53.0"), 53L, false),
                        Arrays.asList("2910800", null, null, true)));
        assertThat(out).startsWith(0xEF, 0xBB, 0xBF);
        assertThat(new String(out, StandardCharsets.UTF_8))
                .isEqualTo("﻿code;value;numerator;suppressed\r\n2927408;53,0;53;false\r\n2910800;;;true\r\n");
    }

    @Test
    void quotesSeparatorsAndNeutralisesFormulas() {
        assertThat(CsvWriter.cell("UBS A; B")).isEqualTo("\"UBS A; B\"");
        assertThat(CsvWriter.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvWriter.cell("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(CsvWriter.cell(new BigDecimal("-12.97"))).isEqualTo("-12,97");
    }
}
