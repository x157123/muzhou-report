package com.muzhou.report.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 导入结果：整包一份汇总 + 每张报表一条明细。见 docs/CONTRACT.md §3.3。
 *
 * <p><b>一张报表失败不影响别的</b>（每张报表一个事务，见 {@code ReportPackageServiceImpl}），
 * 所以结果是一张清单而不是一个成败 —— 批量导入十张、第三张的数据源在正式环境不存在，
 * 剩下九张照样进去，界面上把那一条标出来即可。
 */
@Data
public class ReportImportResultDTO implements Serializable {

    /** 新建的报表数。 */
    private int created;

    /** 覆盖更新的报表数。 */
    private int updated;

    /** 已存在且策略为跳过的报表数。 */
    private int skipped;

    /** 失败的报表数。 */
    private int failed;

    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item implements Serializable {

        private String name;

        private String code;

        /** created / updated / skipped / failed。 */
        private String action;

        /** 失败原因，或跳过的说明。 */
        private String message;

        /** 不影响导入、但需要人去补的事（数据源不存在、公共数据集沿用了目标环境的定义等）。 */
        private List<String> warnings = new ArrayList<>();
    }
}
