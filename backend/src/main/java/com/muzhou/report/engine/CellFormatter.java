package com.muzhou.report.engine;
import com.muzhou.report.dto.CellConfigDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单元格值格式化：生成显示文本 m 与 FortuneSheet 的 ct 描述。
 */
@Slf4j
@Component
public class CellFormatter {

    private static final Map<String, DecimalFormat> NUMBER_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, DateTimeFormatter> DATE_CACHE = new ConcurrentHashMap<>();

    private static final String DEFAULT_NUMBER = "#,##0.##";
    /** 金额默认带人民币符号：符号是模板的一部分（见 {@link #CN_UPPER} 上方说明） */
    private static final String DEFAULT_CURRENCY = "¥#,##0.00";
    private static final String DEFAULT_PERCENT = "0.00";
    private static final String DEFAULT_DATE = "yyyy-MM-dd";

    /**
     * 金额的「中文大写」模板：整格出的是一段文字（壹仟贰佰叁拾肆元伍角陆分），不再是数字。
     *
     * <p>金额的货币符号一律写在模板里（{@code ¥#,##0.00} / {@code $#,##0.00}），
     * 引擎不再另外拼前缀 —— 拼前缀的话符号只进得了显示文本 {@code m}，
     * 进不了 {@code ct.fa}，导出的 Excel 按 fa 重新格式化，符号就没了。
     */
    public static final String CN_UPPER = "[中文大写]";

    /** Unix 时间戳按秒解读的下限：10^8 秒 = 1973-03-03 */
    private static final long EPOCH_SECOND_MIN = 100_000_000L;
    /** Unix 时间戳按毫秒解读的下限：10^11 毫秒 = 1973-03-03，12 位及以上一律当毫秒 */
    private static final long EPOCH_MILLI_MIN = 100_000_000_000L;

    private static final char[] CN_DIGITS = "零壹贰叁肆伍陆柒捌玖".toCharArray();
    /** 节内单位：个十百千 */
    private static final String[] CN_UNITS = {"", "拾", "佰", "仟"};
    /** 节单位：每四位一节，最大到万亿（10^16-1），再大按普通数字输出 */
    private static final String[] CN_SECTIONS = {"", "万", "亿", "万亿"};

    /** 格式化结果。 */
    public record Formatted(String display, Map<String, Object> ct) {
    }

    public Formatted format(Object value, CellConfigDTO cfg) {
        String type = cfg == null || cfg.getFormatType() == null ? "text" : cfg.getFormatType();
        String pattern = cfg == null ? null : cfg.getFormatPattern();
        try {
            return switch (type) {
                case "number" -> numeric(value, blank(pattern) ? DEFAULT_NUMBER : pattern);
                case "currency" -> CN_UPPER.equals(pattern)
                        ? chinese(value)
                        : numeric(value, blank(pattern) ? DEFAULT_CURRENCY : pattern);
                case "percent" -> percent(value, blank(pattern) ? DEFAULT_PERCENT : pattern);
                case "date" -> date(value, blank(pattern) ? DEFAULT_DATE : pattern);
                default -> new Formatted(value == null ? "" : String.valueOf(value), generalCt());
            };
        } catch (Exception e) {
            log.warn("单元格格式化失败 value={} type={} pattern={}", value, type, pattern, e);
            return new Formatted(value == null ? "" : String.valueOf(value), generalCt());
        }
    }

    private Formatted numeric(Object value, String pattern) {
        BigDecimal num = toDecimal(value);
        if (num == null) {
            return new Formatted(value == null ? "" : String.valueOf(value), generalCt());
        }
        DecimalFormat df = NUMBER_CACHE.computeIfAbsent(pattern, DecimalFormat::new);
        String text;
        synchronized (df) {
            text = df.format(num);
        }
        return new Formatted(text, ct(pattern, "n"));
    }

    /**
     * 金额中文大写。出的是文字，所以 {@code ct} 用文本（{@code fa=@ / t=s}）——
     * 标成数字的话导出时 Excel 那头会照着 fa 重新格式化，大写全变回阿拉伯数字。
     */
    private Formatted chinese(Object value) {
        BigDecimal num = toDecimal(value);
        String text = num == null ? null : chineseAmount(num);
        if (text == null) {
            // 非数字、或大到超出万亿：退回带符号的常规金额，别把值吞掉
            return numeric(value, DEFAULT_CURRENCY);
        }
        return new Formatted(text, ct("@", "s"));
    }

    /**
     * 数值转人民币大写（壹仟贰佰叁拾肆元伍角陆分）。
     *
     * <p>四舍五入到分；没有角分的补「整」，有分没角的补「零」（壹佰元零伍分）；负数前缀「负」。
     * 超出万亿位（10^16）返回 null，由调用方退回普通金额格式。
     */
    public static String chineseAmount(BigDecimal value) {
        BigDecimal v = value.setScale(2, RoundingMode.HALF_UP);
        boolean negative = v.signum() < 0;
        v = v.abs();
        BigInteger yuan = v.toBigInteger();
        int cents = v.subtract(new BigDecimal(yuan)).movePointRight(2).intValue();

        String integerPart = chineseInteger(yuan);
        if (integerPart == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (negative) {
            sb.append('负');
        }
        sb.append(integerPart).append('元');
        int jiao = cents / 10;
        int fen = cents % 10;
        if (cents == 0) {
            sb.append('整');
        } else {
            if (jiao > 0) {
                sb.append(CN_DIGITS[jiao]).append('角');
            } else {
                // 壹佰元零伍分：角位为空要补个「零」，否则读成「壹佰元伍分」
                sb.append('零');
            }
            if (fen > 0) {
                sb.append(CN_DIGITS[fen]).append('分');
            }
        }
        return sb.toString();
    }

    /** 整数部分转大写；超出 {@link #CN_SECTIONS} 能表达的位数返回 null。 */
    private static String chineseInteger(BigInteger n) {
        if (n.signum() == 0) {
            return "零";
        }
        BigInteger base = BigInteger.valueOf(10000);
        List<Integer> sections = new ArrayList<>();
        BigInteger rest = n;
        while (rest.signum() > 0) {
            sections.add(rest.mod(base).intValue());
            rest = rest.divide(base);
        }
        if (sections.size() > CN_SECTIONS.length) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean zeroSection = false;
        for (int i = sections.size() - 1; i >= 0; i--) {
            int sec = sections.get(i);
            if (sec == 0) {
                // 整节为零（壹亿零壹）：先记着，等下一个非零节来补这个「零」
                zeroSection = sb.length() > 0;
                continue;
            }
            // 本节不足四位时高位缺的那一截也要读「零」：壹万零壹
            if (sb.length() > 0 && (zeroSection || sec < 1000)) {
                sb.append('零');
            }
            zeroSection = false;
            sb.append(chineseSection(sec)).append(CN_SECTIONS[i]);
        }
        return sb.toString();
    }

    /** 一节（0-9999）转大写，节内连续的零只读一个。 */
    private static String chineseSection(int n) {
        StringBuilder sb = new StringBuilder();
        boolean zero = false;
        for (int p = 3, unit = 1000; p >= 0; p--, unit /= 10) {
            int d = n / unit % 10;
            if (d == 0) {
                zero = sb.length() > 0;
            } else {
                if (zero) {
                    sb.append('零');
                    zero = false;
                }
                sb.append(CN_DIGITS[d]).append(CN_UNITS[p]);
            }
        }
        return sb.toString();
    }

    private Formatted percent(Object value, String pattern) {
        BigDecimal num = toDecimal(value);
        if (num == null) {
            return new Formatted(value == null ? "" : String.valueOf(value), generalCt());
        }
        BigDecimal scaled = num.multiply(BigDecimal.valueOf(100));
        DecimalFormat df = NUMBER_CACHE.computeIfAbsent(pattern, DecimalFormat::new);
        String text;
        synchronized (df) {
            text = df.format(scaled);
        }
        return new Formatted(text + "%", ct(pattern + "%", "n"));
    }

    private Formatted date(Object value, String pattern) {
        if (value == null) {
            return new Formatted("", ct(pattern, "d"));
        }
        LocalDateTime dt = toDateTime(value);
        if (dt == null) {
            // 无法识别的日期原样输出，避免把用户数据吞掉
            return new Formatted(String.valueOf(value), ct(pattern, "d"));
        }
        DateTimeFormatter f = DATE_CACHE.computeIfAbsent(pattern, DateTimeFormatter::ofPattern);
        return new Formatted(f.format(dt), ct(pattern, "d"));
    }

    /** 任意值 -> BigDecimal，非数字返回 null。 */
    public static BigDecimal toDecimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = String.valueOf(v).trim().replace(",", "");
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 任意值 -> LocalDateTime（含 Unix 秒/毫秒时间戳，见 {@link #fromEpoch}），无法识别返回 null。 */
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
        if (v instanceof java.sql.Date sd) {
            return sd.toLocalDate().atStartOfDay();
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (v instanceof Date d) {
            return LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
        }
        if (v instanceof Instant ins) {
            return LocalDateTime.ofInstant(ins, ZoneId.systemDefault());
        }
        // Unix 时间戳：接口返回的日期常是这个（1781488319000 / 1781488319）
        if (v instanceof Number n) {
            return fromEpoch(n.longValue());
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        // 纯数字串同样按 Unix 时间戳解，认不出来（位数不够）时返回 null，与原来一样原样输出
        if (s.chars().allMatch(Character::isDigit)) {
            try {
                return fromEpoch(Long.parseLong(s));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            if (s.length() <= 10) {
                return LocalDate.parse(s.replace('/', '-')).atStartOfDay();
            }
            return LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Unix 时间戳 -&gt; LocalDateTime：12 位及以上按毫秒（1781488319000），
     * 9~11 位按秒（1781488319），按系统时区换算。
     *
     * <p>下限卡在 {@link #EPOCH_SECOND_MIN} 是为了不把 Excel 的日期序列号（45878 这种量级）
     * 误判成 1970 年 —— 小于下限的数返回 null，交回「认不出的日期原样输出」。
     */
    private static LocalDateTime fromEpoch(long v) {
        if (v >= EPOCH_MILLI_MIN) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(v), ZoneId.systemDefault());
        }
        if (v >= EPOCH_SECOND_MIN) {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(v), ZoneId.systemDefault());
        }
        return null;
    }

    /** 数值四舍五入到指定小数位。 */
    public static BigDecimal round(BigDecimal v, int scale) {
        return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
    }

    private Map<String, Object> ct(String fa, String t) {
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("fa", fa);
        ct.put("t", t);
        return ct;
    }

    private Map<String, Object> generalCt() {
        return ct("General", "g");
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
