package com.muzhou.report.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.muzhou.report.common.PageResult;
import com.muzhou.report.dto.DatasetDetailDTO;
import com.muzhou.report.dto.DatasetParseDTO;
import com.muzhou.report.dto.DatasetParseResultDTO;
import com.muzhou.report.dto.DatasetPreviewResultDTO;
import com.muzhou.report.dto.DatasetRowsDTO;
import com.muzhou.report.dto.DatasetSaveDTO;
import com.muzhou.report.entity.MzDataset;
import com.muzhou.report.entity.MzDatasetField;
import com.muzhou.report.entity.MzDatasetParam;

import java.util.List;
import java.util.Map;

/**
 * 数据集管理服务。见 docs/CONTRACT.md §3.2。
 */
public interface DatasetService extends IService<MzDataset> {

    // —— 渲染引擎依赖，签名不可改 ——

    /**
     * 按数据集 code 取数（渲染引擎入口）。
     *
     * @param reportId    发起渲染的报表 id，用于解析它的内部数据集；为空则只认公共数据集
     * @param datasetCode 数据集编码，先在本报表的内部数据集里找，找不到再找公共数据集
     */
    List<Map<String, Object>> fetchDataByCode(String reportId, String datasetCode, Map<String, Object> params);

    /**
     * 同 {@link #fetchDataByCode}，但连分页型数据集的总条数一起带回来。
     *
     * <p>渲染引擎只认「给我 code，还我行数据」这一个签名，拿不走 total —— 所以由
     * {@code RenderServiceImpl} 用这个方法包一层，把各数据集的总数收在渲染这一次的上下文里，
     * 最后只把**主接口**（{@code content.primaryDataset}）那个总数放进渲染结果。
     */
    DatasetRowsDTO fetchRowsByCode(String reportId, String datasetCode, Map<String, Object> params);

    /**
     * 一次解析好的数据集：定义 + 参数定义。
     *
     * <p>存在的理由是**这两样在一次渲染里恒定不变，而取数是按行反复发生的**：
     * {@link #fetchRowsByCode} 每调一次就要 {@code getByCode}（内部集找不到还要再查公共集，
     * 最多 2 条）加一条 {@code listParams}。{@code splitMode=perRow} 200 条数据 × 3 个数据集
     * 就是 1000 多条查询，全都只为拿同一份不会变的元数据。
     *
     * <p>行数据本身不在这里缓存 —— 那是 {@code engine/CachingDataFetcher} 的事，而且它刻意
     * 只记主接口一个（行数据占内存，父子关联的子表每行参数都不同、永远命中不了第二次）。
     * 元数据只有几个字段，全记没有负担。
     */
    record ResolvedDataset(MzDataset dataset, List<MzDatasetParam> params) {
    }

    /**
     * 按 code 解析出数据集定义与它的参数定义（内部数据集优先，其次公共）。
     *
     * @throws com.muzhou.report.common.BizException 数据集不存在
     */
    ResolvedDataset resolve(String reportId, String datasetCode);

    /**
     * 用已经解析好的数据集取数，跳过 {@link #resolve} 那两三条元数据查询。
     *
     * <p>{@link #fetchRowsByCode} 就是 {@code fetchRows(resolve(...), params)}。
     */
    DatasetRowsDTO fetchRows(ResolvedDataset resolved, Map<String, Object> params);

    /**
     * 按数据集 id 取数。
     */
    List<Map<String, Object>> fetchDataById(String datasetId, Map<String, Object> params);

    /**
     * 按 code 查询数据集：本报表的内部数据集优先，其次公共数据集。
     *
     * @param reportId 报表 id，为空表示只在公共数据集里找
     */
    MzDataset getByCode(String reportId, String code);

    /**
     * 查询数据集的字段定义（按 sortNo 升序）。
     */
    List<MzDatasetField> listFields(String datasetId);

    /**
     * 查询数据集的参数定义（按 sortNo 升序）。
     */
    List<MzDatasetParam> listParams(String datasetId);

    /**
     * 分页查询公共数据集（数据集管理页；内部数据集只在它所属报表的设计器里管理）。
     *
     * @param pageNo   页码，从 1 开始
     * @param pageSize 每页条数
     * @param name     名称模糊查询，可为空
     */
    PageResult<MzDataset> page(long pageNo, long pageSize, String name);

    /**
     * 某报表的全部**内部**数据集（不含 fields/params，也不含公共数据集）。
     *
     * <p>与 {@link #listForReport} 的区别：那个是「这张报表能用哪些数据集」（公共 + 内部，
     * 给设计器左侧树用），这个是「哪些数据集属于这张报表」—— 报表被复制/导出时跟着走的就是它们。
     */
    List<MzDataset> listByReport(String reportId);

    /**
     * 查询某报表可用的全部启用数据集（含 fields/params），用于设计器左侧树。
     *
     * @param reportId 报表 id，为空则只返回公共数据集
     */
    List<DatasetDetailDTO> listForReport(String reportId);

    /**
     * 查询单个数据集详情（含 fields/params）。
     */
    DatasetDetailDTO detail(String id);

    /**
     * 新建数据集，返回主键 id。
     */
    String create(DatasetSaveDTO dto);

    /**
     * 更新数据集。
     */
    boolean update(DatasetSaveDTO dto);

    /**
     * 删除数据集（含子表 fields/params）。
     */
    boolean remove(String id);

    /**
     * 删除某报表的全部内部数据集（报表被删除时调用）。公共数据集不受影响。
     */
    void removeByReport(String reportId);

    /**
     * 把源报表的内部数据集整套复制给目标报表（复制报表时调用）。
     *
     * <p>内部数据集是按 report_id 隔离的，不跟着复制的话副本里的 {@code #{code.字段}} 会全部失效；
     * code 保持不变（不同报表之间本来就允许同名）。
     */
    void copyToReport(String fromReportId, String toReportId);

    /**
     * 用这一批数据集**整体替换**某报表的内部数据集（导入报表包时调用）。
     *
     * <p>按 {@code code} 对齐：目标报表里已有同 code 的就原地更新（id 不变，含 fields/params
     * 先删后插），没有的新建，包里没有的删掉 —— 「覆盖」这个词就是这个意思。
     *
     * <p>不走 {@link #create}：导入进来的数据集在源环境本来就存在，不该因为
     * 「编码已被公共数据集占用」这类界面级校验让整张报表导不进来（同 {@link #copyToReport}）。
     *
     * @param datasets 每项的 {@code reportId} 会被强制改成 {@code reportId}，调用方不必填
     */
    void replaceReportDatasets(String reportId, List<DatasetSaveDTO> datasets);

    /**
     * 解析 SQL/接口/JSON，自动发现字段与参数，供设计器编辑数据集时使用。
     */
    DatasetParseResultDTO parse(DatasetParseDTO dto);

    /**
     * 预览取数结果（不入库），供设计器编辑数据集时使用。
     */
    DatasetPreviewResultDTO preview(DatasetParseDTO dto);
}
