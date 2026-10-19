/* 文件职责：严格解析UTF8的SRT/WebVTT字幕，保留原文与毫秒时间，不把字幕当作创作者意图认证。 */
package io.echoinsight.material;

import io.echoinsight.ingestion.ImportDocument;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 只解析上传字节，不请求远程URL、不转码、不执行ASR；非法段使整份导入失败。 */
@Component
public class SubtitleParser {
  public static final int MAX_BYTES = 2 * 1024 * 1024;
  public static final int MAX_SEGMENTS = 5000;
  public static final String INVALID = "字幕格式无效：需要UTF8的SRT或WebVTT，每段时间递增且正文非空";
  public static final String LIMIT = "字幕最多2MiB、5000段、每段10000字、时间范围不超过24小时";
  private static final Pattern TIME =
      Pattern.compile("(?:(\\d{2,3}):)?(\\d{2}):(\\d{2})[.,](\\d{3})");
  private static final Pattern RANGE =
      Pattern.compile("^([^\\s]+)\\s+-->\\s+([^\\s]+)(?:[ \\t]+.*)?$");

  /** raw保留原始解码文本用于哈希；segments按时间及来源段号稳定排序，重叠发言允许存在。 */
  public record Parsed(String format, String raw, List<ImportDocument.Segment> segments) {}

  /** 1、限制字节/解码；2、验证文件类型及VTT头；3、逐块检查时间/正文；4、稳定排序并返回。 */
  public Parsed parse(String format, byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw bad(LIMIT);
    if (!Set.of("SRT", "VTT").contains(format == null ? "" : format)) throw bad(INVALID);
    String raw;
    try {
      raw =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      throw bad(INVALID);
    }
    if (raw.indexOf('\0') >= 0) throw bad(INVALID);
    String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
    if (normalized.startsWith("\ufeff")) normalized = normalized.substring(1);
    if (format.equals("VTT")) {
      String first = normalized.split("\n", 2)[0];
      if (!first.equals("WEBVTT") && !first.startsWith("WEBVTT ") && !first.startsWith("WEBVTT\t"))
        throw bad(INVALID);
      int headerEnd = normalized.indexOf("\n\n");
      if (headerEnd < 0) throw bad(INVALID);
      normalized = normalized.substring(headerEnd + 2);
    }
    List<ImportDocument.Segment> segments = new ArrayList<>();
    int index = 0;
    for (String block : normalized.strip().split("\n[ \\t]*\n+")) {
      if (block.isBlank()) continue;
      String[] lines = block.split("\n", -1);
      if (format.equals("VTT")
          && (lines[0].equals("NOTE")
              || lines[0].startsWith("NOTE ")
              || lines[0].equals("STYLE")
              || lines[0].equals("REGION"))) continue;
      int timing = lines[0].contains("-->") ? 0 : 1;
      if (lines.length <= timing + 1
          || (timing == 1 && format.equals("SRT") && !lines[0].matches("[0-9]{1,9}")))
        throw bad(INVALID);
      var range = RANGE.matcher(lines[timing]);
      if (!range.matches()) throw bad(INVALID);
      long start = time(range.group(1)), end = time(range.group(2));
      if (start >= end) throw bad(INVALID);
      String text = String.join("\n", Arrays.copyOfRange(lines, timing + 1, lines.length));
      if (text.isBlank()) throw bad(INVALID);
      if (text.length() > 10000 || segments.size() >= MAX_SEGMENTS) throw bad(LIMIT);
      segments.add(new ImportDocument.Segment("cue-" + (++index), start, end, text));
    }
    if (segments.isEmpty()) throw bad(INVALID);
    return new Parsed(
        format,
        raw,
        segments.stream()
            .sorted(
                Comparator.comparing(ImportDocument.Segment::startMs)
                    .thenComparing(ImportDocument.Segment::id))
            .toList());
  }

  /** 分钟/秒不能溢出；SRT和VTT统一到原片毫秒，时间不是评论的发布时间。 */
  private long time(String value) {
    var match = TIME.matcher(value);
    if (!match.matches()) throw bad(INVALID);
    long hours = match.group(1) == null ? 0 : Long.parseLong(match.group(1));
    int minutes = Integer.parseInt(match.group(2)), seconds = Integer.parseInt(match.group(3));
    if (minutes >= 60 || seconds >= 60) throw bad(INVALID);
    long result = ((hours * 60 + minutes) * 60 + seconds) * 1000 + Integer.parseInt(match.group(4));
    if (result > 86400000L) throw bad(LIMIT);
    return result;
  }

  /** 固定安全消息不包含文件名、正文或时间原值。 */
  private ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
