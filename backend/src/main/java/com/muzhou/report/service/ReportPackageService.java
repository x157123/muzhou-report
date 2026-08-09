package com.muzhou.report.service;

import com.muzhou.report.dto.ReportImportResultDTO;

import java.util.List;

/**
 * 报表导入导出：把测试环境调好的报表整包带进正式环境。见 docs/CONTRACT.md §3.3。
 *
 * <p>包的结构见 {@link com.muzhou.report.dto.ReportPackageDTO} —— 一个 JSON 文件装 N 张报表，
 * 里面**只有 code 没有主键**，因为两个环境的 UUID 必定对不上。
 */
public interface ReportPackageService {

    /** 目标环境已有同编码报表时：跳过，什么都不动。 */
    String MODE_SKIP = "skip";

    /** 目标环境已有同编码报表时：覆盖它（版式与内部数据集整套替换，报表 id 不变）。 */
    String MODE_OVERWRITE = "overwrite";

    /** 不管有没有同编码报表，一律新建一张（编码撞了就加后缀），两份并存。 */
    String MODE_COPY = "copy";

    /**
     * 导出若干张报表为一个 JSON 包。
     *
     * @param reportIds 报表 id，顺序即包里的顺序；一个也行，批量也行
     * @return 包文件的 UTF-8 字节
     */
    byte[] exportPackage(List<String> reportIds);

    /**
     * 导入一个 JSON 包。
     *
     * <p><b>一张报表一个事务</b>：批量导入时某一张失败（编码非法、内容不是合法 JSON……）
     * 不该把已经导好的那几张一起回滚掉，失败的那条在结果里单独列出来。
     *
     * @param json 包文件的字节
     * @param mode {@link #MODE_SKIP} / {@link #MODE_OVERWRITE} / {@link #MODE_COPY}
     */
    ReportImportResultDTO importPackage(byte[] json, String mode);
}
