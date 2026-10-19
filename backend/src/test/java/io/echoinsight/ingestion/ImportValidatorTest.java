package io.echoinsight.ingestion;

import static org.assertj.core.api.Assertions.*;

import jakarta.validation.Validation;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.*;

class ImportValidatorTest {
  static jakarta.validation.ValidatorFactory factory;
  static ImportValidator validator;
  static final OffsetDateTime TIME = OffsetDateTime.parse("2026-10-15T12:00:00+08:00");

  @BeforeAll
  static void start() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = new ImportValidator(factory.getValidator());
  }

  @AfterAll
  static void stop() {
    factory.close();
  }

  static ImportDocument.Comment comment(String id, String source, String parent, String text) {
    return new ImportDocument.Comment(id, source, parent, text, TIME);
  }

  static ImportDocument doc(List<ImportDocument.Comment> comments) {
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content("video", "SYNTHETIC", "video", "creator", "测试作品", TIME),
        comments,
        List.of());
  }

  @Test
  void collapsesOnlyIdenticalSourceRecords() {
    var a = comment("a", "1", null, "相同文本");
    var b = comment("b", "2", null, "相同文本");
    assertThat(validator.normalize(doc(List.of(a, a, b))).comments()).hasSize(2);
  }

  @Test
  void rejectsConflictingSourceIdsWithRecordPath() {
    assertThatThrownBy(
            () ->
                validator.normalize(
                    doc(List.of(comment("a", "1", null, "原文"), comment("b", "1", null, "修改")))))
        .isInstanceOfSatisfying(
            ImportFailure.class,
            e -> assertThat(e.issues().getFirst().path()).isEqualTo("comments[1].sourceCommentId"));
  }

  @Test
  void rejectsMissingParentAndCycles() {
    assertThatThrownBy(() -> validator.normalize(doc(List.of(comment("a", "1", "missing", "原文")))))
        .isInstanceOf(ImportFailure.class);
    assertThatThrownBy(
            () ->
                validator.normalize(
                    doc(List.of(comment("a", "1", "b", "原文"), comment("b", "2", "a", "回复")))))
        .isInstanceOf(ImportFailure.class);
  }

  @Test
  void acceptsMissingSubtitlesButRejectsEmptyComments() {
    assertThat(
            validator.normalize(doc(List.of(comment("a", "1", null, "原文")))).transcriptSegments())
        .isEmpty();
    assertThatThrownBy(() -> validator.normalize(doc(List.of()))).isInstanceOf(ImportFailure.class);
  }

  @Test
  void rejectsNullEntriesAndBlankText() {
    assertThatThrownBy(
            () -> validator.normalize(doc(java.util.Arrays.asList((ImportDocument.Comment) null))))
        .isInstanceOf(ImportFailure.class);
    assertThatThrownBy(() -> validator.normalize(doc(List.of(comment("a", "1", null, "  ")))))
        .isInstanceOf(ImportFailure.class);
  }

  @Test
  void rejectsInvalidSubtitleRange() {
    var input = doc(List.of(comment("a", "1", null, "原文")));
    var broken =
        new ImportDocument(
            input.schemaVersion(),
            input.content(),
            input.comments(),
            List.of(new ImportDocument.Segment("s", 10L, 5L, "字幕")));
    assertThatThrownBy(() -> validator.normalize(broken)).isInstanceOf(ImportFailure.class);
  }

  @Test
  void rejectsWhitespaceInIdentityFields() {
    assertThatThrownBy(() -> validator.normalize(doc(List.of(comment("a", "1 ", null, "原文")))))
        .isInstanceOf(ImportFailure.class);
  }
}
