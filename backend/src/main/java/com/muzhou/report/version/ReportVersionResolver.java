package com.muzhou.report.version;

import com.muzhou.report.common.BizException;
import com.muzhou.report.dto.VersionConfigDTO;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 版本选择：一张报表有好几份版式时，这一次渲染该用哪一份。
 *
 * <p>纯 POJO，不依赖 Spring 与数据库（照 {@code RenderEngineTest} 的路子直接测）。
 * 渲染引擎完全不知道有版本这回事 —— 选中哪一版是在进引擎**之前**定好的。
 *
 * <p>算法（见 docs/CONTRACT.md §4「版本」）：
 * <ol>
 *   <li>显式 versionId（设计器/预览指定）→ 直接用它，<b>含停用版本</b>，不走下面的规则；</li>
 *   <li>启用版本 ≤ 1 个 → 就是它（或默认版本）—— 这一条同时省掉了下面那次探测取数，
 *       绝大多数报表只有一版，不该为版本功能多打一次 SQL；</li>
 *   <li>取判定值：{@code field} 主接口第一行的该字段 / {@code param} 报表参数 / {@code now} 渲染当日；</li>
 *   <li>归一化成 {@link LocalDateTime}（见 {@link #toDateTime}），解析不了当作取不到；</li>
 *   <li>取不到 → {@code fallback}：{@code default} 用默认版本 / {@code error} 抛 {@link BizException}
 *       （消息里带上是哪个字段没取到）；</li>
 *   <li>命中 = {@code effectiveFrom ≤ 判定值} 的最后一个（<b>左闭右开</b>）；一个都不满足
 *       （早于所有起点）→ {@code effectiveFrom} 为 null 的那一版；没有 null 版 → 默认版本。</li>
 * </ol>
 *
 * <p><b>停用的版本不参与推导</b>，它那段自动被前一版吞掉 —— 这正是「临时回滚版式」想要的行为。
 */
public final class ReportVersionResolver {

    /** 字符串判定值认的格式，见 CONTRACT §4「已知边界」。 */
    private static final DateTimeFormatter[] PATTERNS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
    };

    private ReportVersionResolver() {
    }

    /**
     * 一个候选版本（只带选择用得到的字段，content 不进来 —— 选完才按 id 去捞那一份）。
     */
    public record Candidate(String id, Integer versionNo, String name, LocalDateTime effectiveFrom,
                            boolean isDefault, boolean enabled) {

        /** 界面上的名字：没起名就是 v3。 */
        public String label() {
            return name == null || name.isBlank() ? "v" + versionNo : name;
        }
    }

    /**
     * 选择结果。{@code reason} 是给人看的一句话（预览页显示「当前版本 v2（order_date=2026-06-01 命中）」），
     * 不参与任何判断。
     */
    public record Resolution(Candidate version, LocalDateTime value, String reason) {
    }

    /**
     * 选一版。
     *
     * @param versions          这张报表的全部版本（含停用；顺序无所谓，内部会排）
     * @param explicitVersionId 显式指定的版本 id，空表示按规则自动选
     * @param config            版本切换规则，null 视为默认规则（主接口字段 + 取不到用默认版本）
     * @param primaryDataset    主接口 code（{@code source=field} 时探它），可为空
     * @param params            这一次渲染的参数（{@code source=param} 从里面取值，探测也用它）
     * @param fetcher           取数函数，**外面必须已经包了 {@code CachingDataFetcher}** ——
     *                          否则这次探测就是白白多打一遍主接口
     */
    public static Resolution resolve(List<Candidate> versions,
                                     String explicitVersionId,
                                     VersionConfigDTO config,
                                     String primaryDataset,
                                     Map<String, Object> params,
                                     BiFunction<String, Map<String, Object>, List<Map<String, Object>>> fetcher) {
        if (versions == null || versions.isEmpty()) {
            throw new BizException("报表还没有任何版本");
        }
        VersionConfigDTO cfg = config == null ? VersionConfigDTO.defaults() : config;

        // 1. 显式指定：设计器就是要打开那一版，停用的也照开
        if (explicitVersionId != null && !explicitVersionId.isBlank()) {
            for (Candidate c : versions) {
                if (explicitVersionId.equals(c.id())) {
                    return new Resolution(c, null, "指定版本 " + c.label());
                }
            }
            throw new BizException("版本不存在或不属于该报表: " + explicitVersionId);
        }

        // 2. 只有一版（绝大多数报表）：不必探测取数
        Resolution shortcut = shortcut(versions);
        if (shortcut != null) {
            return shortcut;
        }

        // 3. 取判定值（这一步可能要探一次主接口）
        return resolveByValue(versions, rawValue(cfg, primaryDataset, params, fetcher), cfg);
    }

    /**
     * 判定值**已经拿到**时的选择（{@code perRow} 逐行选版本走这条：每一行自己的日期决定自己那一份
     * 用哪一版版式，不必每行都探一次主接口）。
     *
     * @param raw 判定值的原始形态，会先过 {@link #toDateTime} 归一化
     */
    public static Resolution resolveByValue(List<Candidate> versions, Object raw, VersionConfigDTO config) {
        if (versions == null || versions.isEmpty()) {
            throw new BizException("报表还没有任何版本");
        }
        VersionConfigDTO cfg = config == null ? VersionConfigDTO.defaults() : config;
        Resolution shortcut = shortcut(versions);
        if (shortcut != null) {
            return shortcut;
        }
        Candidate fallbackVersion = defaultVersion(versions);
        List<Candidate> enabled = new ArrayList<>(versions.stream().filter(Candidate::enabled).toList());

        // 4. 归一化
        String label = cfg.getField() == null || cfg.getField().isBlank() ? "判定值" : cfg.getField();
        LocalDateTime value = toDateTime(raw);

        // 5. 取不到：用默认版本 / 直接报错
        if (value == null) {
            if (cfg.isFallbackError()) {
                throw new BizException("版本切换取不到判定值[" + label + "]"
                        + (raw == null ? "" : "（值 " + raw + " 解析不成日期）")
                        + "，请检查报表的版本切换规则");
            }
            return new Resolution(fallbackVersion, null,
                    "判定值[" + label + "]取不到，用默认版本 " + fallbackVersion.label());
        }

        // 6. 命中 effectiveFrom ≤ value 的最后一个（左闭右开）
        enabled.sort(Comparator
                .comparing(Candidate::effectiveFrom, Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder()))
                .thenComparingInt(ReportVersionResolver::no));
        Candidate hit = null;
        for (Candidate c : enabled) {
            // effectiveFrom 为 null 的那一版是「最早的那一版」，恒命中（区间左端是 -∞）
            if (c.effectiveFrom() == null || !c.effectiveFrom().isAfter(value)) {
                hit = c;
            } else {
                break;
            }
        }
        if (hit == null) {
            // 早于所有起点，又没有 null 起点的兜底版 —— 只能退回默认版本
            return new Resolution(fallbackVersion, value,
                    label + "=" + format(value) + " 早于所有版本的生效时间，用默认版本 " + fallbackVersion.label());
        }
        return new Resolution(hit, value, label + "=" + format(value) + " 命中 " + hit.label());
    }

    /**
     * 「不用看判定值就能定下来」的两种情况：一个启用版本都没有（用默认版本）、只有一个启用版本。
     *
     * <p>这一条同时省掉了探测取数 —— 绝大多数报表只有一版，不该为版本功能多打一次 SQL。
     *
     * @return 定不下来时返回 null
     */
    private static Resolution shortcut(List<Candidate> versions) {
        List<Candidate> enabled = versions.stream().filter(Candidate::enabled).toList();
        if (enabled.isEmpty()) {
            Candidate d = defaultVersion(versions);
            return new Resolution(d, null, "没有启用中的版本，用默认版本 " + d.label());
        }
        if (enabled.size() == 1) {
            return new Resolution(enabled.get(0), null, "只有一个启用版本 " + enabled.get(0).label());
        }
        return null;
    }

    /** 默认（基准）版本：标了 is_default 的那个；一个都没标就退回 versionNo 最小的。 */
    public static Candidate defaultVersion(List<Candidate> versions) {
        Candidate min = null;
        for (Candidate c : versions) {
            if (c.isDefault()) {
                return c;
            }
            if (min == null || no(c) < no(min)) {
                min = c;
            }
        }
        return min;
    }

    private static int no(Candidate c) {
        return c.versionNo() == null ? Integer.MAX_VALUE : c.versionNo();
    }

    /** 判定值的原始形态：主接口第一行的字段值 / 报表参数 / 渲染当日。 */
    private static Object rawValue(VersionConfigDTO cfg, String primaryDataset, Map<String, Object> params,
                                   BiFunction<String, Map<String, Object>, List<Map<String, Object>>> fetcher) {
        if (VersionConfigDTO.SOURCE_NOW.equals(cfg.getSource())) {
            return LocalDateTime.now();
        }
        String field = cfg.getField();
        if (field == null || field.isBlank()) {
            return null;
        }
        if (VersionConfigDTO.SOURCE_PARAM.equals(cfg.getSource())) {
            return params == null ? null : params.get(field);
        }
        // source=field：探一次主接口，取**第一行**的这个字段
        if (primaryDataset == null || primaryDataset.isBlank() || fetcher == null) {
            return null;
        }
        List<Map<String, Object>> rows = fetcher.apply(primaryDataset, params);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        if (row == null) {
            return null;
        }
        // 行数据的 key 统一是小写（见 DatasetServiceImpl），但字段名可能是用户按原样填的，两种都试
        return row.containsKey(field) ? row.get(field) : row.get(field.toLowerCase());
    }

    /**
     * 归一化成 {@link LocalDateTime}：{@code java.sql.Date/Timestamp}、{@code LocalDate(Time)}、
     * {@code java.util.Date}、epoch 毫秒、字符串 {@code yyyy-MM-dd[ HH:mm[:ss]]}（也认 ISO 的 {@code T}）。
     *
     * <p>一律按不带时区的本地时间比较，epoch 毫秒按系统时区换算 —— 跨时区部署要另议（CONTRACT §4）。
     * 解析不了返回 null，由调用方按 {@code fallback} 处理。
     */
    public static LocalDateTime toDateTime(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime dt) {
            return dt;
        }
        if (v instanceof LocalDate d) {
            return d.atStartOfDay();
        }
        // java.sql.Date 的 toInstant() 会直接抛异常，必须走 toLocalDate()
        if (v instanceof java.sql.Date sd) {
            return sd.toLocalDate().atStartOfDay();
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (v instanceof java.util.Date ud) {
            return LocalDateTime.ofInstant(ud.toInstant(), ZoneId.systemDefault());
        }
        if (v instanceof Instant ins) {
            return LocalDateTime.ofInstant(ins, ZoneId.systemDefault());
        }
        if (v instanceof Number n) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(n.longValue()), ZoneId.systemDefault());
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        // 纯数字串当 epoch 毫秒（接口返回的日期常是这个）
        if (s.matches("\\d{11,}")) {
            try {
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(s)), ZoneId.systemDefault());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        String normalized = s.replace('T', ' ');
        int dot = normalized.indexOf('.');
        if (dot > 0) {
            // 去掉毫秒尾巴：2026-05-01 00:00:00.0
            normalized = normalized.substring(0, dot);
        }
        for (DateTimeFormatter f : PATTERNS) {
            try {
                return normalized.length() <= 10
                        ? LocalDate.parse(normalized, f).atStartOfDay()
                        : LocalDateTime.parse(normalized, f);
            } catch (Exception ignored) {
                // 换下一个格式
            }
        }
        return null;
    }

    private static String format(LocalDateTime v) {
        return v.toLocalTime().toSecondOfDay() == 0
                ? v.toLocalDate().toString()
                : v.format(PATTERNS[0]);
    }
}
