package com.muzhou.report.dto;

import com.muzhou.report.entity.MzDatasetField;
import com.muzhou.report.entity.MzDatasetParam;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 报表导出包：一个 JSON 文件装 N 张报表（测试环境调好，整包带进正式环境）。见 docs/CONTRACT.md §3.3。
 *
 * <p><b>包里一律按 code 引用，不带任何主键</b> —— 主键是 UUID，两个环境必定对不上：
 * 报表认 {@code code}、数据集认 {@code code}、数据源认 {@link Dataset#datasourceCode}。
 * 导入时先按 code 在目标环境找，找不到才新建（数据源除外，见下）。
 *
 * <p><b>数据源本身不进包</b>：它带着业务库地址与口令，两个环境的连接信息本来就该不一样
 * （口令还是 {@code WRITE_ONLY} 的，压根导不出来）。包里只记数据集绑的是哪个
 * {@code datasourceCode}，目标环境没有这个编码时导入照常进行，只在结果里报一条 warning
 * 让人去补 —— 把整张报表卡住没有意义，版式和数据集都是对的，只差一个连接。
 *
 * <p><b>报表内容里引用到的公共数据集也一起打包</b>（{@link Dataset#shared} 标着）：不带的话
 * 报表进了正式环境就是一张取不到数的空表。但导入时**公共数据集只新建、不覆盖** ——
 * 它被多张报表共用，为了导一张报表改掉别人的取数是灾难。
 */
@Data
public class ReportPackageDTO implements Serializable {

    /** 文件标识，导入时用它认这是不是本项目导出的包。 */
    private String fileType;

    /** 包格式版本，将来结构变了靠它做兼容。 */
    private Integer formatVersion;

    private LocalDateTime exportTime;

    private List<Item> reports = new ArrayList<>();

    /** 一张报表：定义 + 全部版式 + 它用到的数据集。 */
    @Data
    public static class Item implements Serializable {

        private String name;

        /** 报表编码，导入时按它找目标环境里的同一张报表。 */
        private String code;

        private String type;

        /** 版本切换规则（报表级），见 CONTRACT §4「版本」。 */
        private String versionConfig;

        private String remark;

        private Integer status;

        /** 全部版式，按 versionNo 升序。 */
        private List<Version> versions = new ArrayList<>();

        /** 内部数据集（{@code shared=false}）+ 内容里引用到的公共数据集（{@code shared=true}）。 */
        private List<Dataset> datasets = new ArrayList<>();
    }

    /** 一份版式，字段对应 {@code mz_report_version}（去掉 id/report_id/时间戳）。 */
    @Data
    public static class Version implements Serializable {

        private Integer versionNo;

        private String name;

        /** 完整的 ReportContent，见 CONTRACT §4。 */
        private String content;

        /** 生效起始时刻（闭端）；null = 不限。 */
        private LocalDateTime effectiveFrom;

        /** 生效结束时刻（开端）；null = 不限。 */
        private LocalDateTime effectiveTo;

        /** 匹配条件的 JSON 数组串（离散那一维），见 CONTRACT §4.1。 */
        private String matchRules;

        private Integer isDefault;

        private Integer status;

        private String remark;
    }

    /** 一个数据集，字段对应 {@code mz_dataset}（datasource_id 换成 code），含字段/参数子表。 */
    @Data
    public static class Dataset implements Serializable {

        private String name;

        private String code;

        /** true = 公共数据集（导入时只新建不覆盖）；false = 这张报表的内部数据集。 */
        private Boolean shared;

        /** 绑的数据源**编码**；数据源本身不进包，见类注释。 */
        private String datasourceCode;

        private String type;

        private String resultType;

        private String sqlText;

        private String apiUrl;

        private String apiMethod;

        private String apiHeaders;

        private String jsonText;

        private String remark;

        private Integer status;

        private List<MzDatasetField> fields = new ArrayList<>();

        private List<MzDatasetParam> params = new ArrayList<>();
    }
}
