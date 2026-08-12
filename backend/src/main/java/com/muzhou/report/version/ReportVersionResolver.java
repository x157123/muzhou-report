package com.muzhou.report.version;

import com.muzhou.report.common.BizException;
import com.muzhou.report.dto.VersionConfigDTO;
import com.muzhou.report.dto.VersionMatchRuleDTO;

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
 * <p>判定分**两维**：版本自带的**匹配条件**（{@link VersionMatchRuleDTO}，类型/区域这类离散维度）
 * 与**生效时间**（{@code effectiveFrom} ~ {@code effectiveTo} 这个左闭右开的区间，两端都可为空
 * = 不限）。条件先筛、时间后判。
 *
 * <p>算法（见 docs/CONTRACT.md §4.1「版本」）：
 * <ol>
 *   <li>显式 versionId（设计器/预览指定）→ 直接用它，<b>含停用版本</b>，不走下面的规则；</li>
 *   <li>启用版本 ≤ 1 个 → 就是它（或默认版本）—— 这一条同时省掉了下面那次探测取数，
 *       绝大多数报表只有一版，不该为版本功能多打一次 SQL；</li>
 *   <li><b>条件筛选</b>：一个版本的条件要<b>全部</b>满足才算匹配（没配条件 = 无条件匹配）；
 *       匹配到的版本里只留<b>条件数最多</b>的那一批（特异度优先，见 {@link #decide}），
 *       后面的时间判定只在这一批里做；一个都不匹配 → {@code fallback}；</li>
 *   <li>取判定值：{@code field} 主接口第一行的该字段 / {@code param} 报表参数 / {@code now} 渲染当日；</li>
 *   <li>归一化成 {@link LocalDateTime}（见 {@link #toDateTime}），解析不了当作取不到；</li>
 *   <li>取不到 → {@code fallback}：{@code default} 用默认版本 / {@code error} 抛 {@link BizException}
 *       （消息里带上是哪个字段没取到）。<b>但条件已经把那一批筛成唯一一版时就用它</b> ——
 *       条件够定案了，不该再被时间这一维推翻；</li>
 *   <li>命中 = 判定值落在 {@code [effectiveFrom, effectiveTo)} 里的版本。<b>允许多版重叠</b>，
 *       重叠时<b>起点更晚的赢</b>（同起点则版本号大的赢，见 {@link #covers}）；一版都没盖住
 *       （落在空洞里、或晚于所有结束时刻）→ 默认版本（条件筛过的那一批则退回本批里最早的一版
 *       —— 退回默认版本会违背已经命中的条件）。</li>
 * </ol>
 *
 * <p><b>结束时刻是后加的</b>：早先只存起点、右端靠排序推，说不了「这一版 8/1 到期，之后谁也不接」，
 * 也表达不了两版共用同一段时间。而「重叠时起点更晚的赢」与原先那条「取起点 ≤ 判定值的最后一个」
 * 是同一条规则，所以**老数据（结束时刻全为空）行为一字不变**。
 *
 * <p><b>没配判定字段（只按条件选）时时间这一维直接不参与</b>，取匹配那批里生效最晚的一版。
 *
 * <p><b>停用的版本不参与推导</b>，它那段落回别的版本 —— 这正是「临时回滚版式」想要的行为。
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
     *
     * @param effectiveFrom 生效起始（闭端），null = 不限
     * @param effectiveTo   生效结束（<b>开端</b>），null = 不限
     * @param rules         这一版的匹配条件（同一版内多条是 AND），空 = 无条件匹配
     */
    public record Candidate(String id, Integer versionNo, String name, LocalDateTime effectiveFrom,
                            LocalDateTime effectiveTo, boolean isDefault, boolean enabled,
                            List<VersionMatchRuleDTO> rules) {

        public Candidate {
            // 手写的 content 里 "matchRules": [null] 是可能的，List.copyOf 见到 null 元素会抛 NPE
            rules = rules == null ? List.of()
                    : rules.stream().filter(java.util.Objects::nonNull).toList();
        }

        /** 只有起点、右端不限的那种（老数据与测试用这个）。 */
        public Candidate(String id, Integer versionNo, String name, LocalDateTime effectiveFrom,
                         boolean isDefault, boolean enabled, List<VersionMatchRuleDTO> rules) {
            this(id, versionNo, name, effectiveFrom, null, isDefault, enabled, rules);
        }

        /** 没有匹配条件、右端也不限的那种。 */
        public Candidate(String id, Integer versionNo, String name, LocalDateTime effectiveFrom,
                         boolean isDefault, boolean enabled) {
            this(id, versionNo, name, effectiveFrom, null, isDefault, enabled, List.of());
        }

        /** 界面上的名字：没起名就是 v3。 */
        public String label() {
            return name == null || name.isBlank() ? "v" + versionNo : name;
        }

        /** 填完整的那些条件；字段名还空着的条目是界面上没填完的一行，不算数。 */
        public List<VersionMatchRuleDTO> validRules() {
            List<VersionMatchRuleDTO> out = new ArrayList<>();
            for (VersionMatchRuleDTO r : rules) {
                if (r != null && r.isValid()) {
                    out.add(r);
                }
            }
            return out;
        }

        /** 特异度 = 有效条件数。多版同时匹配时**条件更具体的压过更宽泛的**。 */
        public int specificity() {
            return validRules().size();
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

        // 3. 主接口那一行：判定值取自字段、或哪一版的匹配条件要用字段时，才去探这一次
        Map<String, Object> row = needsRow(cfg, versions)
                ? firstRow(primaryDataset, params, fetcher) : null;
        return decide(versions, cfg, timeValue(cfg, row, params), row, params);
    }

    /**
     * 判定值取自**已经拿到的那一行数据**（{@code perRow} 逐行选版本走这条：每一行自己的日期与类型
     * 决定自己那一份用哪一版版式，不必每行都探一次主接口）。
     *
     * @param row    这一行数据（判定字段与 {@code source=field} 的匹配条件都从它取值）
     * @param params 这一次渲染的参数（{@code source=param} 的判定值与匹配条件从它取值）
     */
    public static Resolution resolveByRow(List<Candidate> versions, VersionConfigDTO config,
                                          Map<String, Object> row, Map<String, Object> params) {
        VersionConfigDTO cfg = config == null ? VersionConfigDTO.defaults() : config;
        return decide(versions, cfg, timeValue(cfg, row, params), row, params);
    }

    /**
     * 判定值**已经拿到**时的选择。只有时间这一维 —— 匹配条件要取值，得走
     * {@link #resolveByRow}（拿不到行数据时 {@code source=field} 的条件一律判成不满足）。
     *
     * @param raw 判定值的原始形态，会先过 {@link #toDateTime} 归一化
     */
    public static Resolution resolveByValue(List<Candidate> versions, Object raw, VersionConfigDTO config) {
        VersionConfigDTO cfg = config == null ? VersionConfigDTO.defaults() : config;
        return decide(versions, cfg, raw, null, null);
    }

    /**
     * 两维一起定案：先按匹配条件筛，再在筛出来的那一批里按时间区间推。
     *
     * <p><b>特异度优先</b>：匹配到的版本里只留条件数最多的那一批 —— 「类型=A 且 区域=华东」
     * 压过「类型=A」，压过「无条件」。没有这一条的话，「无条件的那一版」会跟专门配了条件的版本
     * 平起平坐，谁赢要看生效时间，配条件这件事就白做了。
     *
     * @param raw    时间判定值的原始形态
     * @param row    主接口那一行（{@code source=field} 的匹配条件从它取值），可为 null
     * @param params 报表参数（{@code source=param} 的匹配条件从它取值），可为 null
     */
    private static Resolution decide(List<Candidate> versions, VersionConfigDTO cfg, Object raw,
                                     Map<String, Object> row, Map<String, Object> params) {
        if (versions == null || versions.isEmpty()) {
            throw new BizException("报表还没有任何版本");
        }
        Resolution shortcut = shortcut(versions);
        if (shortcut != null) {
            return shortcut;
        }
        Candidate fallbackVersion = defaultVersion(versions);
        String label = cfg.getField() == null || cfg.getField().isBlank() ? "判定值" : cfg.getField();

        // 4. 条件筛选：全部满足才算匹配，再只留特异度最高的那一批
        List<Candidate> group = new ArrayList<>();
        int specificity = -1;
        for (Candidate c : versions) {
            if (!c.enabled() || !matches(c, row, params)) {
                continue;
            }
            int spec = c.specificity();
            if (spec > specificity) {
                specificity = spec;
                group.clear();
            }
            if (spec == specificity) {
                group.add(c);
            }
        }
        if (group.isEmpty()) {
            // 每一版都带条件，而这次的数据一条也不满足
            if (cfg.isFallbackError()) {
                throw new BizException("没有哪一版的匹配条件满足本次数据，请检查各版本的匹配条件");
            }
            return new Resolution(fallbackVersion, null,
                    "没有版本的匹配条件满足，用默认版本 " + fallbackVersion.label());
        }
        // 起点升序（null 排最前 = 不限左端），同起点按版本号升序：**排在后面的赢**，
        // 于是「重叠时起点更晚的赢、同起点后配的赢」就是从头扫一遍留下最后一个命中的
        group.sort(Comparator
                .comparing(Candidate::effectiveFrom, Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder()))
                .thenComparingInt(ReportVersionResolver::no));

        // 5. 只按条件选（判定字段没配）：时间这一维不参与，取本批里生效最晚的那一版
        if (specificity > 0 && !timeEnabled(cfg)) {
            Candidate hit = group.get(group.size() - 1);
            return new Resolution(hit, null, reason(hit, null, label) + " 命中 " + hit.label());
        }

        // 6. 归一化时间判定值
        LocalDateTime value = toDateTime(raw);
        if (value == null) {
            // 条件已经筛成唯一一版：条件够定案了，不必再问时间
            if (specificity > 0 && group.size() == 1) {
                Candidate hit = group.get(0);
                return new Resolution(hit, null, reason(hit, null, label) + " 命中 " + hit.label()
                        + "（判定值[" + label + "]取不到，条件已唯一命中）");
            }
            if (cfg.isFallbackError()) {
                throw new BizException("版本切换取不到判定值[" + label + "]"
                        + (raw == null ? "" : "（值 " + raw + " 解析不成日期）")
                        + "，请检查报表的版本切换规则");
            }
            return new Resolution(fallbackVersion, null,
                    "判定值[" + label + "]取不到，用默认版本 " + fallbackVersion.label());
        }

        // 7. 命中 = 判定值落在 [effectiveFrom, effectiveTo) 里的版本。**允许重叠**，
        //    group 已按「起点升序、同起点按版本号升序」排好，从头扫到尾留下的就是起点最晚的那一版
        Candidate hit = null;
        for (Candidate c : group) {
            if (covers(c, value)) {
                hit = c;
            }
        }
        if (hit == null) {
            if (specificity > 0) {
                // 条件已经命中了这一批，退回默认版本等于把条件推翻 —— 用本批里最早的那一版
                Candidate first = group.get(0);
                return new Resolution(first, value, reason(first, value, label)
                        + " 不在本批任何版本的生效区间内，用其中最早的 " + first.label());
            }
            // 落在空洞里（或早于/晚于所有区间），又没有一版是「全时段」的 —— 只能退回默认版本
            return new Resolution(fallbackVersion, value,
                    label + "=" + format(value) + " 不在任何版本的生效区间内，用默认版本 "
                            + fallbackVersion.label());
        }
        return new Resolution(hit, value, reason(hit, value, label) + " 命中 " + hit.label());
    }

    /** 给人看的那半句：{@code 条件[类型=A 且 区域∈华东,华南] + order_date=2026-06-01}。 */
    private static String reason(Candidate hit, LocalDateTime value, String label) {
        String cond = hit.specificity() > 0 ? "条件[" + VersionMatchRuleDTO.describe(hit.validRules()) + "]" : "";
        String time = value == null ? "" : label + "=" + format(value);
        if (cond.isEmpty()) {
            return time;
        }
        return time.isEmpty() ? cond : cond + " + " + time;
    }

    /** 这一版的匹配条件是不是全都满足（没配条件恒满足）。 */
    private static boolean matches(Candidate c, Map<String, Object> row, Map<String, Object> params) {
        for (VersionMatchRuleDTO r : c.validRules()) {
            Map<String, Object> from = VersionMatchRuleDTO.SOURCE_PARAM.equals(r.getSource()) ? params : row;
            if (!r.test(valueOf(from, r.getField()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判定值在不在这一版的生效区间里：<b>左闭右开</b>，两端为 null 表示那一端不限。
     *
     * <p>两端都为 null = 全时段，恒命中（老数据、以及「只按条件选」的那些版本就是这种）。
     * 区间是**可以重叠**的，谁赢由调用处的排序定（起点更晚的赢），这里只回答「盖没盖住」。
     */
    private static boolean covers(Candidate c, LocalDateTime value) {
        if (c.effectiveFrom() != null && c.effectiveFrom().isAfter(value)) {
            return false;
        }
        return c.effectiveTo() == null || c.effectiveTo().isAfter(value);
    }

    /** 时间这一维参不参与：{@code now} 恒参与，{@code field}/{@code param} 要配了字段名才算。 */
    private static boolean timeEnabled(VersionConfigDTO cfg) {
        return VersionConfigDTO.SOURCE_NOW.equals(cfg.getSource())
                || (cfg.getField() != null && !cfg.getField().isBlank());
    }

    /** 要不要探主接口那一行：判定值取自字段，或哪一版的条件取自字段。 */
    private static boolean needsRow(VersionConfigDTO cfg, List<Candidate> versions) {
        if (cfg.needsProbe() && cfg.getField() != null && !cfg.getField().isBlank()) {
            return true;
        }
        for (Candidate c : versions) {
            if (!c.enabled()) {
                continue;
            }
            for (VersionMatchRuleDTO r : c.rules()) {
                if (r != null && r.isFieldSourced()) {
                    return true;
                }
            }
        }
        return false;
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

    /** 时间判定值的原始形态：主接口那一行的字段值 / 报表参数 / 渲染当日。 */
    private static Object timeValue(VersionConfigDTO cfg, Map<String, Object> row, Map<String, Object> params) {
        if (VersionConfigDTO.SOURCE_NOW.equals(cfg.getSource())) {
            return LocalDateTime.now();
        }
        String field = cfg.getField();
        if (field == null || field.isBlank()) {
            return null;
        }
        return valueOf(VersionConfigDTO.SOURCE_PARAM.equals(cfg.getSource()) ? params : row, field);
    }

    /** 探一次主接口，取**第一行**（判定值与 {@code source=field} 的匹配条件共用这一行）。 */
    private static Map<String, Object> firstRow(String primaryDataset, Map<String, Object> params,
                                                BiFunction<String, Map<String, Object>,
                                                        List<Map<String, Object>>> fetcher) {
        if (primaryDataset == null || primaryDataset.isBlank() || fetcher == null) {
            return null;
        }
        List<Map<String, Object>> rows = fetcher.apply(primaryDataset, params);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    /** 按字段名取值：SQL 数据集的 key 统一是小写（见 DatasetServiceImpl），但用户填的可能是原样，两种都试。 */
    private static Object valueOf(Map<String, Object> from, String field) {
        if (from == null || field == null || field.isBlank()) {
            return null;
        }
        return from.containsKey(field) ? from.get(field) : from.get(field.toLowerCase());
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
