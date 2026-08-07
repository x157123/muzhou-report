package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 父子关联（子接口查询）配置，对应 {@code content.datasetLinks}，见 docs/CONTRACT.md §4。
 *
 * <p>「先查主表、再拿主表返回值去查子表」：主表取回 N 行，子表就被调用 N 次，
 * 第 i 次把 {@link Mapping#getField()} 那个**主表字段的值**塞给子表的
 * {@link Mapping#getParam()} 参数。取数细节见 {@code engine/LinkedDataFetcher}。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DatasetLinkDTO implements Serializable {

    /** 关联名称，只用于设计器里认人。 */
    private String name;

    /** 主表数据集 code。 */
    private String master;

    /** 子表数据集 code。**一个子表只能挂一个主表**，否则取数规则有两种解释。 */
    private String child;

    /** 参数传递：子表参数 ← 主表字段。 */
    private List<Mapping> mappings = new ArrayList<>();

    /** 主子两头都填了、且不是自己关联自己，才是一条能用的关联。 */
    public boolean usable() {
        return master != null && !master.isBlank()
                && child != null && !child.isBlank()
                && !master.equals(child);
    }

    /**
     * 一条参数传递：把主表某个字段的值，作为子表某个参数的值传下去。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Mapping implements Serializable {

        /** 子表参数名（子表 SQL 里的 {@code ${param}} / 接口地址里的 {@code ${param}}）。 */
        private String param;

        /** 主表返回数据里的字段名。 */
        private String field;
    }
}
