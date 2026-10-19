package io.echoinsight.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.ingestion.*;
import io.echoinsight.project.ProjectImportService;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Locale;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** 数据导入控制器。 提供上传 JSON 导入文件以及查询导入结果、快照的 RESTful API。 */
@RestController
@RequestMapping("/api/v1")
public class ImportController {

  // 导入业务逻辑服务
  private final ProjectImportService service;
  // 导入数据持久化映射接口（用于数据库读写）
  private final ImportMapper mapper;
  // 专用于导入解析的、配置了严格安全策略的 JSON 解析器
  private final ObjectMapper parser;

  /**
   * 构造函数：注入依赖并初始化高度安全的独立 JSON 解析器。
   *
   * @param service 导入业务服务
   * @param mapper 数据库映射接口
   * @param json Spring 全局注入的 ObjectMapper（作为基础模板）
   */
  public ImportController(ProjectImportService service, ImportMapper mapper, ObjectMapper json) {
    this.service = service;
    this.mapper = mapper;

    // 1. 基于全局 ObjectMapper 创建独立副本，避免污染全局配置
    // 2. 开启严格反序列化：遇到未知属性或 JSON 尾部多余字符时直接报错
    // 3. 关闭宽松转换：禁止将浮点数（如 1.0）自动转为整型（1）
    this.parser =
        json.copy()
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    // 4. 设置流式读取的安全约束，防止恶意超大 JSON 导致内存溢出（DoS 攻击）
    //    - 最大嵌套深度限制为 64 层
    //    - 单个字符串最大长度限制为 20,000 个字符
    this.parser
        .getFactory()
        .setStreamReadConstraints(
            com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxNestingDepth(64)
                .maxStringLength(20000)
                .build());

    // 5. 配置严格的类型强制转换规则（Coercion Config），彻底封死隐式类型转换漏洞
    // 遍历需要严格控制的逻辑类型：文本(Textual) 和 整型(Integer)
    for (var type :
        java.util.List.of(
            com.fasterxml.jackson.databind.type.LogicalType.Textual,
            com.fasterxml.jackson.databind.type.LogicalType.Integer)) {

      var config = this.parser.coercionConfigFor(type);

      // 如果输入形状是 布尔(Boolean) 或 浮点数(Float)，直接拒绝转换并报错
      for (var shape :
          java.util.List.of(
              com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean,
              com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float)) {
        config.setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
      }

      // 跨类型转换拦截：
      // - 如果目标是文本类型，输入是整型 -> 报错
      // - 如果目标是整型，输入是字符串 -> 报错
      config.setCoercion(
          type == com.fasterxml.jackson.databind.type.LogicalType.Textual
              ? com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer
              : com.fasterxml.jackson.databind.cfg.CoercionInputShape.String,
          com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    }
  }

  /**
   * 上传并处理导入文件接口。
   *
   * @param file 前端通过 multipart/form-data 上传的文件
   * @return 导入成功后的回执（包含导入 ID 等信息），并返回 201 Created 状态
   * @throws IOException 文件读取异常
   */
  @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ImportReceipt> upload(
      @RequestPart("file") MultipartFile file, @RequestParam(required = false) String projectId)
      throws IOException {

    // 校验 1：文件名必须以 .json 结尾（不区分大小写），首版仅支持 JSON 格式
    if (file.getOriginalFilename() == null
        || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".json")) {
      throw new ImportFailure("file", "首版仅支持 .json 文件");
    }

    // 校验 2：文件不能为空
    if (file.isEmpty()) {
      throw new ImportFailure("file", "文件不能为空");
    }

    // 校验 3：文件大小不能超过 2MB，防止恶意大文件上传
    if (file.getSize() > 2 * 1024 * 1024) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    ImportDocument document;
    try {
      // 1. 严格校验文件编码：必须为 UTF-8，遇到格式错误或无法映射的字符直接报错
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(file.getBytes()))
              .toString();

      // 2. 处理 BOM 头：如果文件以 UTF-8 BOM (\uFEFF) 开头，将其剔除，避免 JSON 解析失败
      if (text.startsWith("\uFEFF")) {
        text = text.substring(1);
      }

      // 3. 使用配置了严格安全策略的 parser 将 JSON 字符串反序列化为 ImportDocument 对象
      document = parser.readValue(text, ImportDocument.class);

    } catch (CharacterCodingException e) {
      // 捕获编码异常，返回明确的业务错误提示
      throw new ImportFailure("file", "文件必须使用 UTF-8 编码");
    } catch (JsonProcessingException e) {
      // 捕获 JSON 解析异常，提取出错的 JSON 路径（如 $.users[0].name）
      String path = "$";
      if (e instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
        StringBuilder parts = new StringBuilder();
        for (var ref : mapping.getPath()) {
          if (ref.getFieldName() != null) {
            // 对象字段名拼接，例如：users.name
            if (!parts.isEmpty()) parts.append('.');
            parts.append(ref.getFieldName());
          } else {
            // 数组索引拼接，例如：users[0]
            parts.append('[').append(ref.getIndex()).append(']');
          }
        }
        path = parts.toString();
      }
      // 抛出包含具体出错路径的业务异常
      throw new ImportFailure(path, "JSON 结构、字段类型或时间格式错误；不允许额外字段或多个根对象");
    }

    // 调用业务服务执行数据导入（ingest）
    var result = service.ingest(projectId, document);

    // 返回 201 Created 状态码，并在 Location 头中提供新创建资源的 URI
    return ResponseEntity.created(URI.create("/api/v1/imports/" + result.id())).body(result);
  }

  /**
   * 根据 ID 查询导入回执。
   *
   * @param id 导入任务的唯一标识
   * @return 导入回执对象
   */
  @GetMapping("/imports/{id}")
  public ImportReceipt receipt(@PathVariable String id) {
    // 从数据库查询回执
    var receipt = mapper.receipt(id);

    // 如果不存在，返回 404 Not Found
    if (receipt == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return receipt;
  }

  /**
   * 根据 ID 查询导入数据的原始快照（包含原始 JSON 内容）。
   *
   * @param id 快照的唯一标识
   * @return 快照视图对象
   * @throws JsonProcessingException 原始 JSON 字符串解析为 JsonNode 时可能抛出的异常
   */
  @GetMapping("/snapshots/{id}")
  public SnapshotView snapshot(@PathVariable String id) throws JsonProcessingException {
    // 从数据库查询快照记录
    var row = mapper.snapshot(id);

    // 如果不存在，返回 404 Not Found
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    // 将数据库中存储的原始 JSON 字符串解析为 Jackson 的 JsonNode 树结构，封装到视图中返回
    return new SnapshotView(
        row.id(), row.contentId(), row.inputHash(), parser.readTree(row.documentJson()));
  }

  /**
   * 快照视图记录（Java Record）。 用于对外暴露快照的核心元数据和原始文档内容。
   *
   * @param id 快照 ID
   * @param contentId 内容 ID
   * @param inputHash 原始输入的哈希值（用于校验数据完整性）
   * @param document 原始 JSON 文档的树形结构
   */
  public record SnapshotView(String id, String contentId, String inputHash, JsonNode document) {}
}
