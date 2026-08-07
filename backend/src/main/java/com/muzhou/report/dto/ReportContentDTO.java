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
     * 输出方式：{@code single} 单 sheet 输出（默认）/ {@code perRow} 主接口每条数据一个 sheet /
     * {@code perRowPage} 每条数据一页、拼在同一张 sheet 里。
     *
     * <p>{@code perRow} 是「一条数据一张单据」那种报表：模板整份复制 N 遍，第 i 份只喂主接口的
     * 第 i 行数据，其它数据集每份都拿全量。只对**集合型**主接口有意义 —— 分页型自己就分页了。
     *
     * <p>{@code perRowPage} 拆分方式与 {@code perRow} 完全相同（同一段代码），只是在出口处把那 N 份
     * **首尾相接**拼回每个模板一张（{@code engine/SheetConcat}），并在每份的起始行打一个行分页符，
     * 保证打印时一条数据一页、不会两条挤在同一张纸上。
     */
    private String splitMode;

    /** {@code perRow} 时，sheet 名取主接口这一行的哪个字段（取不到值就退回「第 n 条」）。 */
    private String sheetNameField;

    /**
     * 父子关联（子接口查询）：主表取回的每一行，都拿它的字段值去查一遍子表。
     *
     * <p>只影响**取数**，扩展/公式/格式化一步没变，见 {@code engine/LinkedDataFetcher}。
     */
    private List<DatasetLinkDTO> datasetLinks = new ArrayList<>();

    /** 取第 {@code sheetIndex} 张 sheet 实际生效的打印设置。 */
    public PageConfigDTO pageConfigOf(int sheetIndex) {
        PageConfigDTO own = pageConfigs == null ? null : pageConfigs.get(String.valueOf(sheetIndex));
        return own != null ? own : pageConfig;
    }

    /**
     * 是否「按主接口每条数据拆分」——{@code perRow} 与 {@code perRowPage} 共用同一段拆分代码，
     * 区别只在拆完之后拼不拼（{@link #concatPerRow}）。还得真有主接口，不然不知道按谁拆。
     */
    public boolean splitByRow() {
        return ("perRow".equals(splitMode) || "perRowPage".equals(splitMode))
                && primaryDataset != null && !primaryDataset.isBlank();
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
