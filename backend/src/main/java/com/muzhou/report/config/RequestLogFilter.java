package com.muzhou.report.config;

import com.muzhou.report.common.LogText;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 接口请求 / 返回日志，调试用。
 *
 * <p>每个请求打一条 INFO：方法、路径（带 query）、状态码、耗时，开了 {@code body} 再带上请求体与响应体。
 * <b>一条日志打完整个请求</b>而不是进出各打一条 —— 并发时两条会被别的请求插在中间，对不上号。
 *
 * <p>要读请求体/响应体就必须把流缓存下来（读过一次的流控制器就读不到了），
 * 用 Spring 的 {@link ContentCachingRequestWrapper} / {@link ContentCachingResponseWrapper} 包一层。
 * 代价是响应整份留在内存里，所以导出那几个接口（几 MB 的 xlsx/pdf 字节流）走
 * {@code muzhou.report.log.exclude} 不包装，只记一行摘要。
 *
 * <p>关掉：{@code muzhou.report.log.enabled: false}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "muzhou.report.log", name = "enabled", havingValue = "true", matchIfMissing = true)
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLogFilter extends OncePerRequestFilter {

    /** 能当文本打的响应类型，其余（xlsx / pdf / 图片）只记字节数。 */
    private static final List<String> TEXT_TYPES = List.of("json", "text", "xml", "javascript", "form-urlencoded");

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final MzProperties props;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MzProperties.Log cfg = props.getLog();
        String uri = request.getRequestURI();

        // mask 里的路径两个方向都不打：请求体里带着业务库口令（PUT /api/datasource），
        // 日志的流转范围比数据库宽得多，不该把它抄一份进去
        boolean masked = matches(cfg.getMask(), uri);
        // 请求体：开了 body 且不是文件上传（multipart 打出来是一堆二进制）就缓存
        boolean capReq = cfg.isBody() && !masked && !isMultipart(request);
        // 响应体：exclude 里的（导出）不缓存 —— 请求体照缓存，那是几行 JSON 参数，正是要看的
        boolean capRes = capReq && !matches(cfg.getExclude(), uri);

        ContentCachingRequestWrapper req = capReq ? new ContentCachingRequestWrapper(request) : null;
        ContentCachingResponseWrapper res = capRes ? new ContentCachingResponseWrapper(response) : null;

        long start = System.currentTimeMillis();
        try {
            chain.doFilter(req == null ? request : req, res == null ? response : res);
        } finally {
            long cost = System.currentTimeMillis() - start;
            try {
                logOne(request, response, req, res, cost);
            } catch (Exception e) {
                // 日志本身出问题不能影响响应
                log.warn("打印请求日志失败: {}", e.toString());
            }
            // 缓存的响应体必须回写，否则前端收到空响应
            if (res != null) {
                res.copyBodyToResponse();
            }
        }
    }

    /** include 之外的请求（静态资源等）整条不记。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !matches(props.getLog().getInclude(), request.getRequestURI());
    }

    private void logOne(HttpServletRequest request, HttpServletResponse response,
                        ContentCachingRequestWrapper req, ContentCachingResponseWrapper res, long cost) {
        String query = request.getQueryString();
        String path = request.getRequestURI() + (query == null ? "" : "?" + query);

        StringBuilder sb = new StringBuilder(256);
        sb.append("\n>>> ").append(request.getMethod()).append(' ').append(path)
                .append("  [").append(response.getStatus()).append("]  ").append(cost).append("ms");

        if (req != null) {
            String body = text(req.getContentAsByteArray(), charset(request.getCharacterEncoding()));
            if (!body.isBlank()) {
                sb.append("\n    请求: ").append(truncate(body));
            }
        }
        if (res != null) {
            sb.append("\n    响应: ").append(responseBody(res));
        }
        log.info("{}", sb);
    }

    /** 响应体：文本类型原样打（截断），二进制只记字节数。 */
    private String responseBody(ContentCachingResponseWrapper res) {
        byte[] bytes = res.getContentAsByteArray();
        if (bytes.length == 0) {
            return "<空>";
        }
        String type = res.getContentType();
        if (!isText(type)) {
            return "<" + (type == null ? "二进制" : type) + ", " + bytes.length + " 字节>";
        }
        return truncate(text(bytes, charset(res.getCharacterEncoding())));
    }

    private String truncate(String s) {
        return LogText.abbrev(s, props.getLog().getMaxBody());
    }

    private static String text(byte[] bytes, Charset cs) {
        return bytes.length == 0 ? "" : new String(bytes, cs);
    }

    private static Charset charset(String name) {
        try {
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static boolean isText(String contentType) {
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase();
        return TEXT_TYPES.stream().anyMatch(lower::contains);
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase().startsWith("multipart/");
    }

    private static boolean matches(List<String> patterns, String uri) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        return patterns.stream().anyMatch(p -> MATCHER.match(p, uri));
    }
}
