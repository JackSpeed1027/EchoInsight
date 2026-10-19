package io.echoinsight.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FeedbackMetricsTest {
  @Test
  void excludesUnknownFromDenominator() {
    var metrics = new FeedbackMetrics(40, 20, 20, 20);
    assertThat(metrics.classifiedComments()).isEqualTo(80);
    assertThat(metrics.positiveShareAmongClassified()).isEqualTo(0.5);
  }

  @Test
  void hasNoRatioWhenNoCommentsCanBeClassified() {
    assertThat(new FeedbackMetrics(0, 0, 0, 10).positiveShareAmongClassified()).isNull();
  }

  @Test
  void rejectsNegativeCounts() {
    assertThatThrownBy(() -> new FeedbackMetrics(1, -1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sumsWithoutIntegerOverflow() {
    assertThat(new FeedbackMetrics(Integer.MAX_VALUE, 1, 0, 0).classifiedComments())
        .isEqualTo(2147483648L);
  }
}
