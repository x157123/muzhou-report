package com.muzhou.report.engine;

import com.muzhou.report.engine.model.SheetTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * 把「每条数据一个 sheet」拆出来的那一摞 sheet **首尾相接**拼成一张。
 *
 * <p>用在 {@code content.splitMode=perRowPage}：拆分本身完全复用
 * {@link ReportRenderEngine#renderPerRow} —— 换取数函数、父子关联、缓存那套一行没改，
 * 这里只是在它的**出口**上做一道纯粹的坐标搬移，所以拼接写错了也污染不到普通渲染与 perRow。
 *
 * <p><b>拼接顺序照抄 {@code renderPerRow} 的行优先顺序</b>（r0t0, r0t1, r1t0, r1t1…），
 * 也就是<b>一条数据的几张模板挨在一起</b>，然后才是下一条数据。模板有 3 张时出来是
 * 「数据1的表1/表2/表3、数据2的表1/表2/表3…」—— 一条数据的几张单据连着打，翻页对得上号。
 * 早先是按模板分组（每个模板拼一张、各自摞 N 份），那样出纸变成「全部数据的表1、
 * 全部数据的表2…」，同一条数据的几张纸被拆到工作簿两头去了。
 *
 * <p>跨模板挨着排只能在同一张 sheet 里做（Excel 里 sheet 与 sheet 之间必定断页），
 * 而<b>一张 sheet 只放得下一套打印设置</b>。所以<b>拼接只发生在打印设置相同的相邻份之间</b>：
 * 顺着上面那个顺序走，遇到打印设置与上一份不同就另起一张 sheet。
 * 三张模板都用同一套设置时拼成一张（最常见）；第 2 张单独设成横向，就断成
 * 「数据1的表1 / 数据1的表2(横) / 数据1的表3 / 数据2的表1 / …」这么几张，顺序仍是一条数据一组。
 * <b>不比对打印设置直接拼的话，横向那张会被拼进纵向那张里，导出全变纵向</b> —— 这是这里踩过的坑。
 *
 * <p>拼出来的每张 sheet 都记着自己来自哪张模板（{@code mzTemplateIndex}，由 {@code toSheet}
 * 写在每一份上、拼接时取该组第一份的），下游据此取打印设置
 * （{@code ReportContentDTO#pageConfigOfSheet}）—— 结果 sheet 数不再是模板张数的整数倍，
 * 靠下标取模已经算不出来了。
 *
 * <p>一组里可能有好几张模板（它们打印设置相同），此时<b>列宽逐列取各模板的最大值</b>
 * ——一张 sheet 一列只有一个宽度，取小了窄模板那几列会截字。
 *
 * <p>拼接在 sheet map（celldata + config）这一层做而不是回到 {@code RenderGrid}：
 * celldata 的 {@code v} 是整体搬运的，图片（{@code mzImg}）、{@code ct}、样式全都跟着走，
 * 不必逐项认字段。
 *
 * <p>每一份的起始行记进 {@code sheet.mzRowBreaks}（行分页符），下游据此保证
 * **一份一页**，不会两份挤在同一张纸上 —— 这正是这个模式存在的理由。
 *
 * <p>另有 {@code sheet.mzDocBreaks}：**每份单据**的起始行，页头页尾的 {@code ${page}} /
 * {@code ${pages}} 从那里重编（「本单据第 1 页 共 2 页」）。它是 {@code mzRowBreaks} 的**子集**
 * —— 一条数据有 3 张模板时，3 张各占一页（3 个行分页符），但只有第 1 张是新单据的起点。
 *
 * <p>与它一一对应的还有 {@code sheet.mzDocNames}：每份单据**叫什么**（取
 * {@code content.sheetNameField} 那个字段的值，取不到就是「第 n 条」）。页头页尾里的
 * {@code ${sheet}} 在这个模式下印的是**本单据**的名字而不是工作表名 —— 拼成一张之后
 * 整张 sheet 只有一个名字，而纸是一份份发出去的，每张上该写自己那一份的单号。
 */
public final class SheetConcat {

    private SheetConcat() {
    }

    /**
     * 拼接结果：摞好的 sheet，以及每张是由**传入的第几份**打头的
     * （调用方据此把那一份的打印设置挂到结果 sheet 上）。
     */
    public record Concated(List<Map<String, Object>> sheets, List<Integer> firstIndexes) {
    }

    /**
     * 拼接。三个列表**一一对应**（长度相同）。
     *
     * <p>刻意不按「第 i 份出自第 i%m 张模板」推算 —— 逐行选版本时各行的模板张数可以不同，
     * 那个前提直接崩。每一份出自哪张模板、该用哪份打印设置，由调用方一路记着传进来。
     *
     * @param perRowSheets      {@code renderPerRow} 的产物，**行优先**排列
     *                          （一条数据的几张模板挨着，然后才是下一条数据）
     * @param perSheetTemplates 每一份出自的模板；只拼出一张时还用它恢复被 {@code renderPerRow}
     *                          改掉的 name/id/order/status
     * @param perSheetSetups    每一份生效的打印设置，用来判断相邻两份能不能拼进同一张 sheet
     *                          （只做 equals 比对，传 null = 全都能拼）
     */
    public static Concated concat(List<Map<String, Object>> perRowSheets,
                                  List<SheetTemplate> perSheetTemplates,
                                  List<?> perSheetSetups) {
        return concat(perRowSheets, perSheetTemplates, perSheetSetups, null);
    }

    /**
     * 拼接，并顺手标出每张结果 sheet 里**哪几行起是一份新单据**（{@code mzDocBreaks}）。
     *
     * <p>页码要按单据重编（一条数据一份单据，印的是「本单据第几页 / 共几页」），而拼成一张之后
     * 单据与单据之间只剩行号可以表达这件事 —— {@code mzRowBreaks} 是「每一份的起始行」，
     * 一条数据有 3 张模板时它有 3 个值，其中只有第 1 个才是新单据的起点，两者不能混用。
     *
     * @param perSheetDocIndexes 每一份属于第几条数据；传 null = 不标（结果里没有 {@code mzDocBreaks}，
     *                           下游走整份连续编号的老路）
     */
    public static Concated concat(List<Map<String, Object>> perRowSheets,
                                  List<SheetTemplate> perSheetTemplates,
                                  List<?> perSheetSetups,
                                  List<Integer> perSheetDocIndexes) {
        return concat(perRowSheets, perSheetTemplates, perSheetSetups, perSheetDocIndexes, null);
    }

    /**
     * 拼接，并顺手标出每份单据的起始行（{@code mzDocBreaks}）与**名字**（{@code mzDocNames}）。
     *
     * <p>名字给页头页尾里的 {@code ${sheet}} 用：拼成一张之后整张 sheet 只有一个名字，
     * 而印出来是一份份的单据，每一份该写自己那一份的单号 —— 与「每条数据一个 sheet」
     * 那边由 sheet 名承担的是同一件事，配的也是同一个字段。
     *
     * @param perSheetDocNames 每一份所属单据的名字（与 {@code perSheetDocIndexes} 同长）；
     *                         传 null = 不标（下游退回工作表名）
     */
    public static Concated concat(List<Map<String, Object>> perRowSheets,
                                  List<SheetTemplate> perSheetTemplates,
                                  List<?> perSheetSetups,
                                  List<Integer> perSheetDocIndexes,
                                  List<String> perSheetDocNames) {
        if (perRowSheets == null || perRowSheets.isEmpty()
                || perSheetTemplates == null || perSheetTemplates.size() != perRowSheets.size()) {
            return new Concated(perRowSheets, indexes(perRowSheets));
        }
        boolean markDocs = perSheetDocIndexes != null && perSheetDocIndexes.size() == perRowSheets.size();
        boolean nameDocs = markDocs && perSheetDocNames != null
                && perSheetDocNames.size() == perRowSheets.size();
        List<List<Map<String, Object>>> groups = new ArrayList<>();
        List<List<Boolean>> groupDocStarts = new ArrayList<>();
        List<List<String>> groupDocNames = new ArrayList<>();
        List<SheetTemplate> groupTemplates = new ArrayList<>();
        List<Integer> firstIndexes = new ArrayList<>();
        Object prev = null;
        for (int i = 0; i < perRowSheets.size(); i++) {
            SheetTemplate st = perSheetTemplates.get(i);
            Object setup = perSheetSetups == null || i >= perSheetSetups.size() ? null : perSheetSetups.get(i);
            if (groups.isEmpty() || !Objects.equals(prev, setup)) {
                groups.add(new ArrayList<>());
                groupDocStarts.add(new ArrayList<>());
                groupDocNames.add(new ArrayList<>());
                groupTemplates.add(st);
                firstIndexes.add(i);
            }
            groups.get(groups.size() - 1).add(perRowSheets.get(i));
            // 「新单据从这一份开始」按**全局**相邻两份比对，而不是按组内 —— 打印设置一变就断组，
            // 同一条数据的第 2 张模板可能是新一组的第一份，它不该重编页码
            groupDocStarts.get(groupDocStarts.size() - 1).add(markDocs
                    && (i == 0 || !perSheetDocIndexes.get(i).equals(perSheetDocIndexes.get(i - 1))));
            groupDocNames.get(groupDocNames.size() - 1).add(nameDocs ? perSheetDocNames.get(i) : null);
            prev = setup;
        }
        List<Map<String, Object>> out = new ArrayList<>(groups.size());
        for (int g = 0; g < groups.size(); g++) {
            // 只有一组时（各模板打印设置相同）拼完就是「这份报表」，名字/编号回到模板第 1 张；
            // 断成多组时保留每组第一份自己的名字与 id —— renderPerRow 已经保证它们互不重名
            out.add(stack(groups.get(g), groupTemplates.get(g), groups.size() == 1, g,
                    markDocs ? groupDocStarts.get(g) : null,
                    nameDocs ? groupDocNames.get(g) : null));
        }
        return new Concated(out, firstIndexes);
    }

    /**
     * 老签名：按「第 i 份出自第 i%m 张模板」展开后走上面那条路。
     *
     * <p>只在「每一份都把那 m 张模板整批渲染了一遍」时成立，<b>眼下只有测试在用</b> ——
     * 逐行换版式（各版模板张数不同）与清单页（{@code sheetSplits} 里标了 {@code once} 的那张
     * 只出一份，后面全错位）都会让这个推算崩掉。生产代码一律走上面那个由调用方
     * 一路把模板与打印设置传进来的版本。
     *
     * @param pageSetupOf 按**模板**下标取打印设置，传 null = 全都能拼
     */
    public static List<Map<String, Object>> concat(List<Map<String, Object>> perRowSheets,
                                                   List<SheetTemplate> templates,
                                                   IntFunction<Object> pageSetupOf) {
        if (perRowSheets == null || perRowSheets.isEmpty() || templates == null || templates.isEmpty()) {
            return perRowSheets;
        }
        int m = templates.size();
        List<SheetTemplate> perSheet = new ArrayList<>(perRowSheets.size());
        List<Object> setups = new ArrayList<>(perRowSheets.size());
        for (int i = 0; i < perRowSheets.size(); i++) {
            SheetTemplate st = templates.get(i % m);
            perSheet.add(st);
            setups.add(pageSetupOf == null ? null : pageSetupOf.apply(st.getSheetIndex()));
        }
        return concat(perRowSheets, perSheet, setups).sheets();
    }

    /** 0..n-1，用在「原样返回、没真的拼」的那条早返回路径上。 */
    private static List<Integer> indexes(List<?> list) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; list != null && i < list.size(); i++) {
            out.add(i);
        }
        return out;
    }

    /**
     * 把一组（打印设置相同、可能跨数据跨模板）自上而下摞成一张。
     *
     * @param docStarts 与 {@code copies} 一一对应：这一份是不是一份新单据的开头（页码从它重编）；
     *                  null = 不标 {@code mzDocBreaks}
     * @param docNames  与 {@code copies} 一一对应：这一份所属单据叫什么（只在它是单据开头时取用）；
     *                  null = 不标 {@code mzDocNames}
     */
    private static Map<String, Object> stack(List<Map<String, Object>> copies, SheetTemplate st,
                                             boolean single, int order, List<Boolean> docStarts,
                                             List<String> docNames) {
        // 以第一份为底：raw 上那些不涉及坐标的属性（frozen 等）照抄
        Map<String, Object> sheet = new LinkedHashMap<>(copies.get(0));
        if (single) {
            // renderPerRow 给每一份都改过名、重编过 id/order/status，拼成一张后那套编号就该丢掉
            sheet.put("name", st.getName());
            sheet.put("id", st.getId());
        }
        sheet.put("order", order);
        sheet.put("status", order == 0 ? 1 : 0);
        // 取打印设置认这个值，别靠结果下标推算（断成几张之后就不是模板张数的整数倍了）
        sheet.put("mzTemplateIndex", st.getSheetIndex());

        List<Map<String, Object>> celldata = new ArrayList<>();
        Map<String, Object> merge = new LinkedHashMap<>();
        Map<String, Object> rowlen = new LinkedHashMap<>();
        Map<String, Object> columnlen = new LinkedHashMap<>();
        List<Object> borderInfo = new ArrayList<>();
        List<Integer> breaks = new ArrayList<>();
        List<Integer> docBreaks = new ArrayList<>();
        // 与 docBreaks 一一对应：从那一行起的这份单据叫什么
        List<String> docNameList = new ArrayList<>();

        int off = 0;
        int maxCol = -1;
        for (int i = 0; i < copies.size(); i++) {
            Map<String, Object> copy = copies.get(i);
            Map<String, Object> config = asMap(copy.get("config"));
            final int base = off;
            if (i > 0) {
                // 第一份从第 0 行开始，它前面不需要分页符
                breaks.add(base);
            }
            if (docStarts != null && Boolean.TRUE.equals(docStarts.get(i))) {
                // 单据的起点是分页符的**子集**：一条数据的第 2、3 张模板各占一页，但页码接着数
                docBreaks.add(base);
                String name = docNames == null ? null : docNames.get(i);
                docNameList.add(name == null ? "" : name);
            }

            for (Object o : rawList(copy.get("celldata"))) {
                Map<String, Object> moved = shiftCell(asMap(o), base);
                if (moved != null) {
                    celldata.add(moved);
                    Integer c = num(moved.get("c"));
                    if (c != null) {
                        maxCol = Math.max(maxCol, c);
                    }
                }
            }

            for (Object val : asMap(config.get("merge")).values()) {
                Map<String, Object> mm = shiftMerge(asMap(val), base);
                if (mm != null) {
                    merge.put(mm.get("r") + "_" + mm.get("c"), mm);
                }
            }

            asMap(config.get("rowlen")).forEach((k, v) -> {
                Integer r = num(k);
                if (r != null) {
                    rowlen.put(String.valueOf(r + base), v);
                }
            });

            for (Object b : rawList(config.get("borderInfo"))) {
                Object moved = shiftBorder(asMap(b), base);
                if (moved != null) {
                    borderInfo.add(moved);
                }
            }

            // 拼接只动行，所以同一模板的各份列宽完全相同；但几张模板摞进同一张 sheet 后
            // 一列只能有一个宽度，逐列取最大 —— 取小了窄模板那几列会截字，宁可宽一点
            asMap(config.get("columnlen")).forEach((k, v) -> {
                Integer now = num(v);
                Integer had = num(columnlen.get(k));
                if (had == null || (now != null && now > had)) {
                    columnlen.put(k, v);
                }
            });

            off += height(copy);
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("merge", merge);
        config.put("rowlen", rowlen);
        config.put("columnlen", columnlen);
        config.put("borderInfo", borderInfo);

        sheet.put("celldata", celldata);
        sheet.put("config", config);
        sheet.put("mzRowBreaks", breaks);
        if (docStarts != null) {
            sheet.put("mzDocBreaks", docBreaks);
        }
        if (docNames != null) {
            sheet.put("mzDocNames", docNameList);
        }
        // 留出余量，与 ReportRenderEngine#toSheet 同一条规则
        sheet.put("row", Math.max(60, off + 10));
        sheet.put("column", Math.max(20, maxCol + 10));
        return sheet;
    }

    /**
     * 这一份占了多少行。
     *
     * <p>celldata / merge / rowlen / borderInfo 四处取最大：只看 celldata 会吃掉「有边框或调过行高、
     * 但没有值」的尾行，下一份就叠到它上面去了。
     */
    static int height(Map<String, Object> sheet) {
        int max = -1;
        for (Object o : rawList(sheet.get("celldata"))) {
            Integer r = num(asMap(o).get("r"));
            if (r != null) {
                max = Math.max(max, r);
            }
        }
        Map<String, Object> config = asMap(sheet.get("config"));
        for (Object val : asMap(config.get("merge")).values()) {
            Map<String, Object> m = asMap(val);
            Integer r = num(m.get("r"));
            Integer rs = num(m.get("rs"));
            if (r != null) {
                max = Math.max(max, r + (rs == null ? 1 : rs) - 1);
            }
        }
        for (Object k : asMap(config.get("rowlen")).keySet()) {
            Integer r = num(k);
            if (r != null) {
                max = Math.max(max, r);
            }
        }
        for (Object b : rawList(config.get("borderInfo"))) {
            for (Object rObj : rawList(asMap(b).get("range"))) {
                List<Object> row = rawList(asMap(rObj).get("row"));
                if (row.size() >= 2) {
                    Integer e = num(row.get(1));
                    if (e != null) {
                        max = Math.max(max, e);
                    }
                }
            }
        }
        return max + 1;
    }

    /**
     * 搬一个 celldata 条目。
     *
     * <p>三样都要动，漏一样就是这块最典型的 bug：{@code r} 本身、{@code v.mc.r}
     * （合并区要 config.merge 与格子上的 mc **两份对得上**，缺一边 FortuneSheet 画不出合并）、
     * 以及 {@code v.f} 里的原生公式引用（不偏移的话第 2 份起的公式全指向第 1 份）。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> shiftCell(Map<String, Object> cd, int off) {
        Integer r = num(cd.get("r"));
        if (r == null) {
            return null;
        }
        Map<String, Object> moved = new LinkedHashMap<>(cd);
        moved.put("r", r + off);
        if (off == 0) {
            return moved;
        }
        Object vObj = cd.get("v");
        if (vObj instanceof Map) {
            Map<String, Object> v = new LinkedHashMap<>((Map<String, Object>) vObj);
            Object mcObj = v.get("mc");
            if (mcObj instanceof Map) {
                Map<String, Object> mc = new LinkedHashMap<>((Map<String, Object>) mcObj);
                Integer mr = num(mc.get("r"));
                if (mr != null) {
                    mc.put("r", mr + off);
                }
                v.put("mc", mc);
            }
            Object f = v.get("f");
            if (f instanceof String s && !s.isBlank()) {
                v.put("f", A1RefUtils.shiftFormula(s, row -> row + off, col -> col));
            }
            moved.put("v", v);
        }
        return moved;
    }

    private static Map<String, Object> shiftMerge(Map<String, Object> m, int off) {
        Integer r = num(m.get("r"));
        Integer c = num(m.get("c"));
        if (r == null || c == null) {
            return null;
        }
        Map<String, Object> moved = new LinkedHashMap<>(m);
        moved.put("r", r + off);
        return moved;
    }

    /**
     * 偏移一条边框的行坐标。
     *
     * <p>两种形态都要偏（见 {@code ExcelExporter#computeBorders}）：{@code range} 形态偏行区间，
     * {@code rangeType=cell}（逐格边框）偏 {@code value.row_index} —— 只管前一种的话，
     * 复制粘贴出来的那些边框会全部堆在第一份的位置上。
     */
    private static Object shiftBorder(Map<String, Object> border, int off) {
        if (border.isEmpty()) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(border);
        if ("cell".equals(border.get("rangeType"))) {
            Map<String, Object> value = asMap(border.get("value"));
            Integer r = num(value.get("row_index"));
            if (r != null) {
                Map<String, Object> moved = new LinkedHashMap<>(value);
                moved.put("row_index", r + off);
                copy.put("value", moved);
            }
            return copy;
        }
        List<Object> newRanges = new ArrayList<>();
        for (Object rObj : rawList(border.get("range"))) {
            Map<String, Object> range = asMap(rObj);
            if (range.isEmpty()) {
                newRanges.add(rObj);
                continue;
            }
            Map<String, Object> moved = new LinkedHashMap<>(range);
            List<Object> row = rawList(range.get("row"));
            if (row.size() >= 2) {
                Integer s = num(row.get(0));
                Integer e = num(row.get(1));
                if (s != null && e != null) {
                    moved.put("row", List.of(s + off, e + off));
                }
            }
            newRanges.add(moved);
        }
        copy.put("range", newRanges);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    /** 原样取一个 List，不做元素类型过滤（{@code range.row} 装的是数字，不是 Map）。 */
    @SuppressWarnings("unchecked")
    private static List<Object> rawList(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    private static Integer num(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
