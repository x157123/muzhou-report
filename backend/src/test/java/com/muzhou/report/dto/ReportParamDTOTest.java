package com.muzhou.report.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局参数与报表参数的合并（CONTRACT §5「参数值从哪来」）。
 *
 * <p>合并结果直接决定两件事：引擎按谁的默认值填、参数表单画出几个输入框。
 * 这里锁的就是「同名怎么办」——最容易写成「两条都留着」或者「反过来是全局赢」。
 */
class ReportParamDTOTest {

    private ReportParamDTO param(String name, String text, String defaultValue) {
        ReportParamDTO p = new ReportParamDTO();
        p.setName(name);
        p.setText(text);
        p.setDefaultValue(defaultValue);
        return p;
    }

    @Test
    @DisplayName("两边没有同名的就首尾相接，全局在前")
    void concatsWhenNoConflict() {
        List<ReportParamDTO> merged = ReportParamDTO.merge(
                List.of(param("companyName", "公司抬头", "木舟")),
                List.of(param("orderId", "订单号", "")));

        assertEquals(2, merged.size());
        assertEquals("companyName", merged.get(0).getName());
        assertEquals("orderId", merged.get(1).getName());
    }

    @Test
    @DisplayName("同名时报表那条整条覆盖全局那条，不是只换默认值")
    void reportDefinitionWinsWholesale() {
        ReportParamDTO reportOne = param("deptId", "本表部门", "D2");
        List<ReportParamDTO> merged = ReportParamDTO.merge(
                List.of(param("deptId", "部门", "D1")),
                List.of(reportOne));

        assertEquals(1, merged.size());
        // 整条换掉：显示名也得是报表那条的，否则会落到「标签是全局的、值是报表的」这种半拉状态
        assertSame(reportOne, merged.get(0));
        assertEquals("本表部门", merged.get(0).getText());
        assertEquals("D2", merged.get(0).getDefaultValue());
    }

    @Test
    @DisplayName("覆盖时保留全局那条的位置，参数表单的顺序不跟着跳")
    void overrideKeepsGlobalPosition() {
        List<ReportParamDTO> merged = ReportParamDTO.merge(
                List.of(param("a", "A", ""), param("b", "B", "")),
                List.of(param("b", "B2", ""), param("c", "C", "")));

        assertEquals(List.of("a", "b", "c"), merged.stream().map(ReportParamDTO::getName).toList());
        assertEquals("B2", merged.get(1).getText());
    }

    @Test
    @DisplayName("没名字的定义直接丢掉：它进了列表只会在引擎里被跳过，还占一个表单位置")
    void dropsNamelessDefinitions() {
        List<ReportParamDTO> merged = ReportParamDTO.merge(
                List.of(param(null, "没名字", ""), param("  ", "空白", "")),
                List.of(param("ok", "有名字", "")));

        assertEquals(1, merged.size());
        assertEquals("ok", merged.get(0).getName());
    }

    @Test
    @DisplayName("任一边为 null 都当没有，报表没声明过参数时就只剩全局那份")
    void toleratesNulls() {
        assertTrue(ReportParamDTO.merge(null, null).isEmpty());
        assertEquals(1, ReportParamDTO.merge(List.of(param("g", "G", "")), null).size());
        assertEquals(1, ReportParamDTO.merge(null, List.of(param("r", "R", ""))).size());
    }
}
