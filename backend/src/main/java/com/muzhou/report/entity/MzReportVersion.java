package com.muzhou.report.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 报表版本：一张报表的一份**版式**（content），见 docs/CONTRACT.md §2。
 *
 * <p>版本化的是版式而不是数据集 —— 数据集是「取数」，跨版本共用；一旦分叉，主接口、父子关联、
 * 分页 total、内部/公共作用域全要跟着分叉，而实际诉求（「5 月起单据换个抬头 / 加一列」）
 * 几乎都是版式。
 *
 * <p>生效区间**两端都存**（{@link #effectiveFrom} / {@link #effectiveTo}，左闭右开，
 * 两端都可为空 = 不限）。早先只存起点、右端靠排序推，说不了「这一版 8/1 到期，之后谁也不接」，
 * 也表达不了两版共用同一段时间。**允许重叠**，重叠时起点更晚的赢（同起点则版本号大的赢）——
 * 这与原先「取起点 ≤ 判定值的最后一个」是同一条规则，所以老数据（结束时刻全为空）行为不变。
 * **停用的版本不参与自动选择**，它那段落回别的版本，这正是「临时回滚版式」想要的行为。
 */
@Data
@TableName("mz_report_version")
public class MzReportVersion implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属报表 */
    private String reportId;

    /** 报表内唯一，展示成 v1/v2/v3。发号要含已逻辑删除的行，见 uk_version_report_no */
    private Integer versionNo;

    /** 版本名，空则显示 v{versionNo} */
    private String name;

    /** 这一版完整的 ReportContent，见 CONTRACT §4 */
    private String content;

    /** 生效起始时刻（闭端）；null = 不限，从最早算起 */
    private LocalDateTime effectiveFrom;

    /** 生效结束时刻（**开端**，这一刻起不再生效）；null = 不限，一直有效 */
    private LocalDateTime effectiveTo;

    /**
     * 匹配条件（{@code VersionMatchRuleDTO} 的 JSON 数组），空 = 无条件匹配。
     *
     * <p>时间那一维只说得清「什么时候起换版式」，说不清「类型 A 走这版、类型 B 走那版」，
     * 所以再给每一版挂一组条件（同一版内多条是 AND）：**条件先筛、时间后推**，
     * 多版同时匹配时条件更具体的压过更宽泛的。见 CONTRACT §4.1。
     */
    private String matchRules;

    /** 基准版本，报表内恰好一条；判定值取不到时用它 */
    private Integer isDefault;

    /** 1 启用（参与自动选择）/ 0 停用（只能在设计器里显式打开） */
    private Integer status;

    private String remark;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    private String createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
