/* 文件职责：验证新内容协议的阶段、摘要容量、引用闭包与旧请求编码兼容。 */
package io.echoinsight.content;

import static org.assertj.core.api.Assertions.*;

import io.echoinsight.model.ModelContract;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 全部使用合成数据，不作为独立语义质量标签。 */
class ContentFullContractTest {
  /** MAP仅接收当前片原段，正常结构可验证。 */
  @Test
  void validatesMapAndItsEvidence() {
    ContentFullContract.validateRequest(plan(), map());
    ContentFullContract.validate(map(), response(List.of(finding("s1", 20))));
  }

  /** 片摘要必须足够短，不能到汇总阶段再静默截取。 */
  @Test
  void rejectsOversizedMapSummary() {
    assertThatThrownBy(
            () -> ContentFullContract.validate(map(), response(List.of(finding("s1", 401)))))
        .hasMessageContaining("MODEL_CONTENT_INVALID");
    assertThatThrownBy(
            () ->
                ContentFullContract.validate(
                    map(), response(Collections.nCopies(4, finding("s1", 20)))))
        .hasMessageContaining("MODEL_CONTENT_INVALID");
  }

  /** MAP不能引其他片/其他版本的段或重复引用。 */
  @Test
  void rejectsForeignOrRepeatedMapEvidence() {
    assertThatThrownBy(
            () -> ContentFullContract.validate(map(), response(List.of(finding("foreign", 20)))))
        .hasMessageContaining("MODEL_CONTENT_INVALID");
    var f =
        new ContentModelContract.Finding(
            "归纳", ContentModelContract.Kind.OBSERVATION, "解释", List.of("s1", "s1"));
    assertThatThrownBy(() -> ContentFullContract.validate(map(), response(List.of(f))))
        .isInstanceOf(ModelContract.Failure.class);
  }

  /** 汇总能引用后片原文，但不能凭空引用未进入中间摘要的段。 */
  @Test
  void validatesReduceAcrossAllSummariesAndRejectsNewEvidence() {
    var reduce = reduce(List.of(summary(0, "s1"), summary(1, "tail")));
    ContentFullContract.validateRequest(plan(), reduce);
    ContentFullContract.validate(reduce, response(List.of(finding("tail", 1000))));
    assertThatThrownBy(
            () -> ContentFullContract.validate(reduce, response(List.of(finding("unseen", 20)))))
        .hasMessageContaining("MODEL_CONTENT_INVALID");
  }

  /** 汇总摘要编号必须0起连续、全部存在，不能悄悄漏片或重排。 */
  @Test
  void rejectsMissingOrReorderedSummaries() {
    assertThatThrownBy(
            () -> ContentFullContract.validateRequest(plan(), reduce(List.of(summary(1, "s1")))))
        .hasMessageContaining("MODEL_INVALID_REQUEST_BOUNDARY");
    assertThatThrownBy(
            () ->
                ContentFullContract.validateRequest(
                    plan(), reduce(List.of(summary(0, "s1"), summary(0, "s2")))))
        .hasMessageContaining("MODEL_INVALID_REQUEST_BOUNDARY");
  }

  /** 不允许混合阶段输入，v1计划不能调用v2。 */
  @Test
  void rejectsProtocolAndPhaseMismatch() {
    var invalid =
        new ContentFullContract.Request(
            ContentFullContract.PROMPT,
            "m",
            "材料",
            "TXT",
            "声明",
            "总结",
            "REDUCE",
            1,
            map().selection(),
            List.of(summary(0, "s1")),
            10000,
            4096);
    assertThatThrownBy(() -> ContentFullContract.validateRequest(plan(), invalid))
        .hasMessageContaining("MODEL_INVALID_REQUEST_BOUNDARY");
    var old =
        new ModelContract.Plan(
            "FIXTURE",
            "fixture",
            ContentModelContract.PROMPT,
            ContentModelContract.TAXONOMY,
            1,
            5000,
            4096,
            "SIMULATED_UNITS",
            "fixture",
            1,
            1,
            100000,
            10000,
            "fixture",
            "LOCAL_STUB");
    assertThatThrownBy(() -> ContentFullContract.validateRequest(old, map()))
        .hasMessageContaining("MODEL_INVALID_REQUEST_BOUNDARY");
  }

  /** 用量超界和空摘要不能当成功缓存，已发送后的费用交给原账本。 */
  @Test
  void rejectsInvalidUsageAndEmptyOutput() {
    assertThatThrownBy(
            () ->
                ContentFullContract.validate(
                    map(),
                    new ContentModelContract.Response(
                        List.of(finding("s1", 20)), List.of(), 10001, 2, "fixture")))
        .hasMessageContaining("MODEL_CONTENT_INVALID");
    assertThatThrownBy(() -> ContentFullContract.validate(map(), response(List.of())))
        .hasMessageContaining("MODEL_CONTENT_INVALID");
  }

  /** 新阶段字段不添加到旧Request，防止历史成功模型批次指纹改变。 */
  @Test
  void oldRequestWireShapeDoesNotAcquireFullFields() {
    var old =
        new ContentModelContract.Request(
            ContentModelContract.PROMPT,
            "m",
            "材料",
            "TXT",
            "声明",
            "总结",
            map().selection(),
            10000,
            4096);
    assertThat(ModelContract.encode(old)).doesNotContain("batchIndex", "summaries", "phase");
  }

  /** 固定本地模拟计划，无供应商费用。 */
  private ModelContract.Plan plan() {
    return new ModelContract.Plan(
        "FIXTURE",
        "fixture",
        ContentFullContract.PROMPT,
        ContentFullContract.TAXONOMY,
        40,
        5000,
        4096,
        "SIMULATED_UNITS",
        "fixture",
        1,
        1,
        1000000,
        10000,
        "fixture",
        "LOCAL_STUB");
  }

  /** 原文不可信但不会被执行。 */
  private ContentFullContract.Request map() {
    var selection =
        new ContentSelection(
            "content-full-window-v1",
            "m",
            "a",
            "b",
            null,
            null,
            1,
            1,
            0,
            0,
            2,
            List.of(new ContentMaterialParser.Segment("s1", "原文", 0, 2, null, null)));
    return new ContentFullContract.Request(
        ContentFullContract.PROMPT,
        "m",
        "材料",
        "TXT",
        "声明",
        "总结",
        "MAP",
        0,
        selection,
        null,
        10000,
        4096);
  }

  /** 汇总不携带原文selection，避免二次整篇出站。 */
  private ContentFullContract.Request reduce(List<ContentFullContract.ChunkSummary> summaries) {
    return new ContentFullContract.Request(
        ContentFullContract.PROMPT,
        "m",
        "材料",
        "TXT",
        "声明",
        "总结",
        "REDUCE",
        summaries.size(),
        null,
        summaries,
        10000,
        4096);
  }

  /** 合成片摘要仍指向原文ID。 */
  private ContentFullContract.ChunkSummary summary(int index, String id) {
    return new ContentFullContract.ChunkSummary(index, List.of(finding(id, 20)), List.of());
  }

  /** 有界合成归纳。 */
  private ContentModelContract.Finding finding(String id, int chars) {
    return new ContentModelContract.Finding(
        "归纳", ContentModelContract.Kind.OBSERVATION, "字".repeat(chars), List.of(id));
  }

  /** 响应用量来自夹具，验证结构而非语义。 */
  private ContentModelContract.Response response(List<ContentModelContract.Finding> findings) {
    return new ContentModelContract.Response(findings, List.of(), 2, 3, "fixture");
  }
}
