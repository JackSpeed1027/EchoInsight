/* 文件职责：严格解析独立文本/字幕，保留原始身份及原文定位，不依赖评论快照。 */
package io.echoinsight.content;

import io.echoinsight.material.SubtitleParser;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 文本按UTF16偏移分段，字幕按来源时间分段；不下载视频、不执行ASR、不认证作者身份。 */
@Component
public class ContentMaterialParser {
  public static final int MAX_CHARS = 200000;
  private final SubtitleParser subtitles;

  /** 复用已验收SRT/VTT解析，不复制时间格式规则。 */
  public ContentMaterialParser(SubtitleParser subtitles) {
    this.subtitles = subtitles;
  }

  /** 一段原文；文本给[startChar,endChar)，字幕给[startMs,endMs)，两种定位不能互换。 */
  public record Segment(
      String id, String text, Integer startChar, Integer endChar, Long startMs, Long endMs) {}

  /** raw保留解码原文，包括换行/BOM；正文段只提供对应定位，不改写历史文件。 */
  public record Parsed(String format, String raw, List<Segment> segments) {}

  /** 1、字节及编码校验；2、字幕复用原解析；3、文本按完整Unicode边界分段；4、限制总正文。 */
  public Parsed parse(String format, byte[] bytes) {
    if (!Set.of("TXT", "MD", "SRT", "VTT").contains(format == null ? "" : format))
      throw bad("仅支持UTF8 TXT、MD、SRT、VTT");
    if (bytes == null || bytes.length == 0 || bytes.length > SubtitleParser.MAX_BYTES)
      throw bad("文件为1字节～2MiB");
    String raw;
    try {
      raw =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException error) {
      throw bad("文件必须是有效UTF8编码");
    }
    if (raw.isBlank() || raw.indexOf('\0') >= 0) throw bad("材料不能为空或包含NUL字符");
    List<Segment> segments = new ArrayList<>();
    if (format.equals("SRT") || format.equals("VTT")) {
      for (var cue : subtitles.parse(format, bytes).segments())
        segments.add(new Segment(cue.id(), cue.text(), null, null, cue.startMs(), cue.endMs()));
    } else {
      if (raw.length() > MAX_CHARS) throw bad("独立文本最多200000个UTF16字符");
      int start = raw.startsWith("\ufeff") ? 1 : 0;
      while (start < raw.length()) {
        int end = Math.min(start + 2000, raw.length());
        if (end < raw.length() && Character.isHighSurrogate(raw.charAt(end - 1))) end--;
        String text = raw.substring(start, end);
        if (!text.isBlank())
          segments.add(new Segment("text-" + (segments.size() + 1), text, start, end, null, null));
        start = end;
      }
    }
    long chars = segments.stream().mapToLong(item -> item.text().length()).sum();
    if (segments.isEmpty() || chars > MAX_CHARS) throw bad("材料需有正文，最多200000个UTF16字符");
    return new Parsed(format, raw, List.copyOf(segments));
  }

  /** 固定业务错误不输出上传正文或本机文件路径。 */
  private ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
