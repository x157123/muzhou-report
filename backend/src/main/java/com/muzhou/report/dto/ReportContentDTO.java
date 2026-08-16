package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表内容，对应 mz_report.content，见 docs/CONTRACT.md §4。
 *
 * <p>sheets 保持 FortuneSheet 的原始结构（用 Map 承载），引擎不做强类型建模，
 * 这样前端升级 FortuneSheet 版本、新增样式属性时后端无需跟着改。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportContentDTO implements Serializable {

    private Integer version = 1;

    /** FortuneSheet Sheet[] 原样 */
    private List<Map<String, Object>> sheets = new ArrayList<>();

    /** key = `${sheetIndex}_${r}_${c}` */
    private Map<String, CellConfigDTO> cellConfigs = new LinkedHashMap<>();

    private List<ReportParamDTO> params = new ArrayList<>();

    /**
     * 主接口：本报表用来驱动分页的那个数据集 code，**一张报表只有一个**（单值天然唯一）。
     *
     * <p>只有它的分页信息（总条数）会体现在预览页的分页条上，翻页时 {@code pageNo} / {@code pageSize}
     * 作为报表参数传下去 —— 接口地址里用 {@code ${pageNo}} 接。为空表示这张报表不分页。
     *
     * <p>标记记在报表里而不是数据集上：公共数据集被多张报表共用，它在 A 报表里是主接口
     * 不代表在 B 报表里也是。
     */
    private String primaryDataset;

    /**
     * 输出方式：{@code single} 单 sheet 输出（默认）/ {@code perRowPage} 每条数据一页、
     * 拼在同一张 sheet 里（<b>设计器界面上叫「多 sheet 输出」</b>）/ {@code perRow}
     * 每条数据一个 sheet。
     *
     * <p>「一条数据一张单据」那种报表：模板整份复制 N 遍，第 i 份只喂主接口的第 i 行数据，
     * 其它数据集每份都拿全量。只对**集合型**主接口有意义 —— 分页型自己就分页了。
     * <b>两个拆分模式共用这一段代码，区别只在出口</b>：
     * <ul>
     *   <li>{@code perRow} 不拼接，直接出 M×N 张 sheet（M = 模板张数），导出的 Excel 里
     *       就是那么多标签页；顺序是行优先（一条数据的几张模板挨着）。</li>
     *   <li>{@code perRowPage} 把那 N 份**首尾相接**拼回每个模板一张
     *       （{@code engine/SheetConcat}），并在每份的起始行打一个行分页符，保证打印时
     *       一条数据一页、不会两条挤在同一张纸上；<b>打印设置不同的相邻份才会断成几张 sheet</b>
     *       ——「多 sheet 输出」这个名字说的是这件事，不是「一条数据一张工作表」。</li>
     * </ul>
     */
    private String splitMode;

    /**
     * 单据名取主接口这一行的哪个字段（取不到值就退回「第 n 条」）：{@code perRowPage} 拿它当
     * {@code mzDocNames}（页头页尾里的 {@code ${sheet}}），{@code perRow} 拿它当 sheet 名。
     */
    private String sheetNameField;

    /**
     * <b>哪几张模板参与按行拆分</b>，key = 模板下标（字符串，与 {@link #pageConfigs} 同一套寻址），
     * 值 {@code once} = 不参与（整份只渲染一次，主接口拿<b>全量</b>数据）/ {@code perRow} = 参与（默认）。
     *
     * <p>「第一张模板是清单列表、第二张是每条数据的详情」这种报表的出口：{@link #splitMode} 是
     * <b>报表级</b>的总开关（拆不拆、拆完拼不拼），拆的时候<b>哪几张跟着拆</b>由这一项按模板决定。
     * 没有它的话 {@code perRow} 会把清单页也复制 N 遍、每份还只喂一行数据 —— 清单就不成其为清单了。
     *
     * <p><b>只存「改过的」那些模板</b>，缺省即 {@code perRow} —— 老报表一个字都没有，行为不变。
     */
    private Map<String, String> sheetSplits = new LinkedHashMap<>();

    /**
     * 第 {@code sheetIndex} 张模板参不参与按行拆分（缺省参与）。
     *
     * <p>只在 {@link #splitByRow()} 为真时有意义；{@code single} 输出下每张模板本来就只渲染一次。
     */
    public boolean splitsSheet(int sheetIndex) {
        String v = sheetSplits == null ? null : sheetSplits.get(String.valueOf(sheetIndex));
        return !"once".equals(v);
    }

    /**
     * 父子关联（子接口查询）：主表取回的每一行，都拿它的字段值去查一遍子表。
     *
     * <p>只影响**取数**，扩展/公式/格式化一步没变，见 {@code engine/LinkedDataFetcher}。
     */
    private List<DatasetLinkDTO> datasetLinks = new ArrayList<>();

    /**
     * 导出设置：下载下来的 Excel / PDF / Word 叫什么名字（报表名 + 主接口的若干字段值）。
     *
     * <p>与 {@link #splitMode} 一样是**报表级**的（不按 sheet 分）—— 一次导出只出一个文件。
     */
    private ExportConfigDTO exportConfig = new ExportConfigDTO();

    /**
     * 导出的文件名（不含扩展名）。没配过导出设置的老报表就是报表名本身。
     *
     * @param reportName 报表名
     * @param row        主接口那一行，可以为 null（没有主接口、没配字段、一条数据都没取到、
     *                   或者<b>主接口不止一行</b>，见 {@link ExportConfigDTO#nameRow}）
     * @param params     这次渲染用的参数，`${参数名}` 那几段从这里取；可以为 null
     */
    public String exportFileName(String reportName, Map<String, Object> row, Map<String, Object> params) {
        ExportConfigDTO cfg = exportConfig == null ? new ExportConfigDTO() : exportConfig;
        return cfg.resolve(reportName, row, params);
    }

    /** 取第 {@code sheetIndex} 张 sheet 实际生效的打印设置。 */
    public PageConfigDTO pageConfigOf(int sheetIndex) {
        PageConfigDTO own = pageConfigs == null ? null : pageConfigs.get(String.valueOf(sheetIndex));
        return own != null ? own : pageConfig;
    }

    /**
     * 是否「按主接口每条数据拆分」——{@code perRow} 与 {@code perRowPage} 共用同一段拆分代码，
     * 区别只在拆完之后拼不拼（{@link #concatPerRow}）。还得真有主接口，不然不知道按谁拆。
     *
     * <p>还得<b>真有一张模板参与拆分</b>（{@link #splitsSheet}）：整份都标了 {@code once} 时
     * 拆分是恒等变换，直接退回普通渲染那条路 —— 少一次主接口探测，也少给下游一堆
     * 「一份单据」的标记。
     */
    public boolean splitByRow() {
        if (!("perRow".equals(splitMode) || "perRowPage".equals(splitMode))
                || primaryDataset == null || primaryDataset.isBlank()) {
            return false;
        }
        int count = sheets == null ? 0 : sheets.size();
        for (int i = 0; i < count; i++) {
            if (splitsSheet(i)) {
                return true;
            }
        }
        return false;
    }

    /** 拆完是否首尾相接拼回每个模板一张 sheet（每条数据之间打行分页符）。 */
    public boolean concatPerRow() {
        return "perRowPage".equals(splitMode) && primaryDataset != null && !primaryDataset.isBlank();
    }

    /** 结果里的 sheet 数是否为模板的 N 倍 —— 只有拆成多张 sheet 的 {@code perRow} 是。 */
    public boolean splitPerRow() {
        return splitByRow() && !concatPerRow();
    }

    /**
     * 取**渲染结果**里这张 sheet 生效的打印设置。**导出与预览一律走这个方法。**
     *
     * <p>结果 sheet 与模板 sheet 不是一一对应的（拆分是 N 倍、拼接又会把几份合成一张），
     * 而 {@link #pageConfigs} 是按**模板**下标存的，所以每张结果 sheet 自己记着出自哪张模板
     * （{@code mzTemplateIndex}，见 {@code ReportRenderEngine#toSheet} / {@code SheetConcat}）。
     * 认这个值就不必再按下标推算 —— 拼接模式下结果 sheet 数已经不是模板张数的整数倍了。
     *
     * @param sheet         渲染结果里的那张 sheet，可以为 null（老结果里没有这个标记）
     * @param renderedIndex 它在渲染结果里的下标，仅当 sheet 上没有标记时用来兜底
     */
    public PageConfigDTO pageConfigOfSheet(Map<String, Object> sheet, int renderedIndex) {
        Object t = sheet == null ? null : sheet.get("mzTemplateIndex");
        if (t instanceof Number n) {
            return pageConfigOf(n.intValue());
        }
        return pageConfigOfRendered(renderedIndex);
    }

    /**
     * 按结果下标推算的兜底版本，只在 sheet 上没有 {@code mzTemplateIndex} 时用
     * （手工构造的渲染结果、老数据）—— 新代码优先用 {@link #pageConfigOfSheet}。
     *
     * <p>{@code perRow} 拆出来的结果 sheet 数是模板的 N 倍（N = 主接口行数），按
     * {@code renderedIndex % 模板张数} 映射回模板下标；直接拿结果下标去查的话，
     * 第二条数据开始就全退回报表级默认值了。{@code perRowPage} 拼过之后这个推算不成立
     * （所以 {@link #splitPerRow} 把它排除在外），它必须靠 {@code mzTemplateIndex}。
     *
     * <p><b>混着 {@code once} 模板时（清单 + 明细）这个推算同样不成立</b> —— 清单那张只出一份，
     * 后面的下标全错位了。那种结果一定带着 {@code mzTemplateIndex} 与
     * {@code RenderResult.sheetPageConfigs}，走不到这里。
     */
    public PageConfigDTO pageConfigOfRendered(int renderedIndex) {
        int templateCount = sheets == null ? 0 : sheets.size();
        if (!splitPerRow() || templateCount <= 0) {
            return pageConfigOf(renderedIndex);
        }
        return pageConfigOf(renderedIndex % templateCount);
    }

    /** 报表级打印设置：没单独设过的 sheet 都用它（也是老报表唯一的一份设置） */
    private PageConfigDTO pageConfig = new PageConfigDTO();

    /**
     * 按 sheet 单独设置的打印设置，key = sheet 下标（字符串）。
     *
     * <p>只存「改过的」那些 sheet，其余退回 {@link #pageConfig}。与 {@link #cellConfigs}
     * 一样按下标寻址，也一样不跟随 sheet 的增删移动。
     */
    private Map<String, PageConfigDTO> pageConfigs = new LinkedHashMap<>();
}
