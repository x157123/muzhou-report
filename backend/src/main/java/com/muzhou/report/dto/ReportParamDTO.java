package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表参数定义，见 docs/CONTRACT.md §4。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportParamDTO implements Serializable {

    private String name;
    private String text;
    /** string | number | date | boolean */
    private String type = "string";
    /** input | number | date | daterange | select */
    private String widget = "input";
    private String defaultValue = "";
    private Boolean required = Boolean.FALSE;
    private List<Map<String, Object>> options = new ArrayList<>();

    /**
     * 把全局参数（{@code mz_param}）与报表参数（{@code content.params}）合成一份定义。见 CONTRACT §5。
     *
     * <p>同名时**报表那条整条覆盖全局那条**（类型/控件/必填/选项一起换），不是只换默认值 ——
     * 只换默认值会落到「显示名和控件是全局的、值却按报表的类型转」这种半拉状态。
     *
     * <p>不能简单地把两份接起来交给 {@code ReportRenderEngine#mergeParams}：那边是「谁先填上算谁的」，
     * 同名参数会在列表里留下两条，参数表单跟着画出两个同名输入框。
     *
     * <p>覆盖时**保留全局那条的位置**（{@link LinkedHashMap} 的 put 语义）：参数表单上的先后顺序
     * 因此不会随「这张报表有没有覆盖它」而跳动。
     */
    public static List<ReportParamDTO> merge(List<ReportParamDTO> global, List<ReportParamDTO> report) {
        Map<String, ReportParamDTO> merged = new LinkedHashMap<>();
        putAll(merged, global);
        putAll(merged, report);
        return new ArrayList<>(merged.values());
    }

    private static void putAll(Map<String, ReportParamDTO> target, List<ReportParamDTO> defs) {
        if (defs == null) {
            return;
        }
        for (ReportParamDTO def : defs) {
            if (def != null && def.getName() != null && !def.getName().isBlank()) {
                target.put(def.getName(), def);
            }
        }
    }
}
