package com.muzhou.report.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.muzhou.report.common.PageResult;
import com.muzhou.report.entity.MzReport;

/**
 * 报表管理服务。见 docs/CONTRACT.md §3.3。
 */
public interface ReportService extends IService<MzReport> {

    /**
     * 分页查询报表（不含 content，避免列表接口传输大字段）。
     *
     * @param pageNo   页码，从 1 开始
     * @param pageSize 每页条数
     * @param name     名称模糊查询，可为空
     */
    PageResult<MzReport> page(long pageNo, long pageSize, String name);

    /**
     * 查询报表详情（content 取**默认版本**的那一份）。
     */
    MzReport getDetail(String id);

    /**
     * 查询报表详情，content 取指定版本的那一份。
     *
     * <p>报表内容按版本存（{@code mz_report_version.content}），这里回填的 {@code content}
     * 与 {@code versionId} 说明「这是哪一版」。{@code versionId} 为空 = 默认版本。
     */
    MzReport getDetail(String id, String versionId);

    /**
     * 查询报表详情，并指明**报表不存在时要不要当场建一张空白的**。
     *
     * <p>自动创建是为嵌入准备的（外部系统拿业务 KEY 直接开设计器），但它让 GET 带上了
     * 写副作用，所以只有「设计器/预览打开报表」那条路该开：
     * <ul>
     *   <li>{@code GET /api/report/{id}} → {@code true}（上面两个重载走的就是这条）</li>
     *   <li>渲染 / 导出 / 查参数 → {@code false}。渲染一张不存在的报表应当报错，
     *       而不是凭空建一张再出一份空模板 —— 那会让「报表 id 写错了」这种事
     *       悄无声息地过去，还顺手往库里留下一行垃圾。</li>
     * </ul>
     *
     * <p>全局开关见 {@code muzhou.report.auto-create}，关掉之后这个参数传 true 也不建。
     */
    MzReport getDetail(String id, String versionId, boolean autoCreate);

    /**
     * 新建报表，返回主键 id。
     */
    String create(MzReport report);

    /**
     * 更新报表。
     */
    boolean update(MzReport report);

    /**
     * 删除报表（逻辑删除）。
     */
    boolean remove(String id);

    /**
     * 复制报表：name 追加「_副本」，code 追加「_copy_时间戳」，content 原样复制。
     *
     * @return 新报表 id
     */
    String copy(String id);
}
