package com.phishlens.scanapi.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScoreRoundingTest {

    // Expected values are Python's round(x, 2). BigDecimal.valueOf gets the first four wrong.
    @ParameterizedTest(name = "round({0}, 2) == {1}")
    @CsvSource({
            "0.155, 0.15",
            "0.165, 0.17",
            "0.005, 0.01",
            "2.675, 2.67",
            "0.125, 0.12",
            "0.275, 0.28",
            "0.7550000000000001, 0.76",
            "0.9480000000000001, 0.95"})
    void matchesPythonRound(double value, String python) {
        assertThat(ScoreRounding.round(value)).isEqualTo(new BigDecimal(python));
    }
}
