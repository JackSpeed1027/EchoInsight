/* 文件职责：把用户 URL 收敛为 B站视频身份；不发起网络请求，也不跟随重定向。 */
package io.echoinsight.collection;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** URL 白名单是 SSRF 与命令注入边界：执行器只接收解析后的 BV/av 身份。 */
@Component
public class BilibiliUrlParser {
  private static final Pattern SOURCE = Pattern.compile("(?:BV[0-9A-Za-z]{10}|av[1-9][0-9]{0,19})");

  /** 规范结果保留用户意图和稳定 URL，查询参数不会进入命令、日志或幂等指纹。 */
  public record Target(String sourceId, String canonicalUrl) {}

  /** 1、限制长度；2、要求 HTTPS 官方域名；3、匹配单一视频路径；4、输出纯身份。 */
  public Target parse(String input) {
    if (input == null || input.isBlank() || input.length() > 500)
      throw bad("请输入长度不超过 500 字符的 B站视频链接");
    try {
      URI uri = new URI(input.strip());
      String host = uri.getHost();
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || host == null
          || !(host.equalsIgnoreCase("www.bilibili.com") || host.equalsIgnoreCase("bilibili.com"))
          || uri.getPort() != -1
          || uri.getUserInfo() != null) throw bad("仅支持 https://www.bilibili.com/video/ 下的视频链接");
      String path = uri.getRawPath();
      if (path == null || path.contains("%")) throw bad("视频路径不能包含编码字符");
      String[] parts = path.split("/");
      if (parts.length != 3 || !"video".equals(parts[1]) || !SOURCE.matcher(parts[2]).matches())
        throw bad("链接需要包含有效的 BV 号或 av 号");
      return new Target(parts[2], "https://www.bilibili.com/video/" + parts[2]);
    } catch (URISyntaxException ignored) {
      throw bad("B站视频链接格式不正确");
    }
  }

  private ResponseStatusException bad(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
