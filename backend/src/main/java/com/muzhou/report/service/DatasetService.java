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
     * @param versionId   正在渲染的是哪一版，用于解析**版本级**数据集；为空则只认报表级与公共
     * @param datasetCode 数据集编码，解析顺序见 {@link #getByCode}
     */
    List<Map<String, Object>> fetchDataByCode(String reportId, String versionId, String datasetCode,
                                              Map<String, Object> params);

    /**
     * 同 {@link #fetchDataByCode}，但连分页型数据集的总条数一起带回来。
     *
     * <p>渲染引擎只认「给我 code，还我行数据」这一个签名，拿不走 total —— 所以由
     * {@code RenderServiceImpl} 用这个方法包一层，把各数据集的总数收在渲染这一次的上下文里，
     * 最后只把**主接口**（{@code content.primaryDataset}）那个总数放进渲染结果。
     */
    DatasetRowsDTO fetchRowsByCode(String reportId, String versionId, String datasetCode,
                                   Map<String, Object> params);

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
     * 按 code 解析出数据集定义与它的参数定义（作用范围由窄到宽，见 {@link #getByCode}）。
     *
     * @throws com.muzhou.report.common.BizException 数据集不存在
     */
    ResolvedDataset resolve(String reportId, String versionId, String datasetCode);

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
     * 按 code 查询数据集，作用范围**由窄到宽**：这一版自己的 → 本报表全版本共用的 → 公共的。
     *
     * <p>「某一版换个接口」就是靠这条顺序做到的：那一版下建一个同 code 的数据集把上层盖住，
     * 模板里的 {@code #{code.字段}} 不用动。
     *
     * @param reportId  报表 id，为空表示只在公共数据集里找
     * @param versionId 版本 id，为空表示不看版本级那一层
     */
    MzDataset getByCode(String reportId, String versionId, String code);

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
     * 查询**某报表的某一版**可用的全部启用数据集（含 fields/params），用于设计器左侧树。
     *
     * <p>三类：公共 + 该报表全版本共用 + 这一版自己的。别的版本自己的那些不在其中。
     *
     * @param reportId  报表 id，为空则只返回公共数据集
     * @param versionId 版本 id，为空则不含任何版本级数据集
     */
    List<DatasetDetailDTO> listForReport(String reportId, String versionId);

    /**
     * 某报表下**归属于某一版**的数据集（不含全版本共用的，也不含公共的）。
     *
     * <p>版本被复制/删除时跟着走的就是它们。
     */
    List<MzDataset> listByVersion(String reportId, String versionId);

    /**
     * 这一版有没有自己的数据集。
     *
     * <p>给渲染那头判「换了版本要不要重新绑定取数」用：绝大多数报表一个版本级数据集都没有，
     * 这时候换版本不影响取数，探测时取回的那份可以接着用（见 {@code RenderServiceImpl#renderSaved}）。
     */
    boolean hasVersionDatasets(String reportId, String versionId);

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
     * 删除某报表的全部内部数据集（报表被删除时调用），含各版本自己的那些。公共数据集不受影响。
     */
    void removeByReport(String reportId);

    /**
     * 删除某一版自己的数据集（版本被删除时调用）。全版本共用的那些不受影响。
     */
    void removeByVersion(String reportId, String versionId);

    /**
     * 把某一版自己的数据集整套复制给同一张报表的另一版（复制版本时调用）。
     *
     * <p>不跟着复制的话，新版本里那些 {@code #{code.字段}} 会掉到报表级/公共那一层去取数 ——
     * 「从这一版派生」的语义就断了。
     */
    void copyToVersion(String reportId, String fromVersionId, String toVersionId);

    /**
     * 把源报表的内部数据集整套复制给目标报表（复制报表时调用）。
     *
     * <p>内部数据集是按 report_id 隔离的，不跟着复制的话副本里的 {@code #{code.字段}} 会全部失效；
     * code 保持不变（不同报表之间本来就允许同名）。
     *
     * @param versionIdMap 源报表版本 id → 副本里对应版本 id，用来安置**版本级**的那些。
     *                     所以复制报表时必须**先复制版本**再复制数据集
     */
    void copyToReport(String fromReportId, String toReportId, Map<String, String> versionIdMap);

    /**
     * 用这一批数据集**整体替换**某报表的内部数据集（导入报表包时调用）。
     *
     * <p>按 {@code (versionId, code)} 对齐：目标报表里同一作用范围下已有同 code 的就原地更新
     * （id 不变，含 fields/params 先删后插），没有的新建，包里没有的删掉 —— 「覆盖」就是这个意思。
     * 只按 code 对齐不行：同一张报表里 v1 与 v2 可以各有一个 {@code orders}。
     *
     * <p>不走 {@link #create}：导入进来的数据集在源环境本来就存在，不该因为
     * 「编码已被公共数据集占用」这类界面级校验让整张报表导不进来（同 {@link #copyToReport}）。
     *
     * @param datasets 每项的 {@code reportId} 会被强制改成 {@code reportId}，调用方不必填；
     *                 {@code versionId} 要由调用方填好（导入时是包里的 versionNo 映射出来的新版本 id）
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
