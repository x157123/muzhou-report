package com.muzhou.report.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.muzhou.report.common.BizException;
import com.muzhou.report.common.PageResult;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.entity.MzReport;
import com.muzhou.report.entity.MzReportVersion;
import com.muzhou.report.mapper.MzReportMapper;
import com.muzhou.report.service.DatasetService;
import com.muzhou.report.service.ReportService;
import com.muzhou.report.service.ReportVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 报表管理服务实现。见 docs/CONTRACT.md §3.3。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl extends ServiceImpl<MzReportMapper, MzReport> implements ReportService {

    /**
     * 报表编码规则：字母/数字/下划线/中划线/点，1~50 位（`code` 列是 varchar(50)）。
     *
     * <p><b>刻意比数据源、数据集那两处宽</b>：它们的 code 是人在界面上起的名字，可以要求
     * 「字母开头」；报表的 code 却常常**不是人起的** —— 外部系统拿自己的业务 KEY 直接开设计器时
     * ，{@code autoCreate} 把那个 KEY 同时当 id 和 code 用，而业务 KEY 多半是雪花号这类纯数字
     * （见 {@code util/SpreadsheetImportTool} 同步过来的那批）。用「字母开头」去卡，嵌入这条路
     * 第一步就走不通 —— 这正是这段校验当初被整段注释掉的原因。
     *
     * <p>放宽不等于不校验：仍然拦住空值（否则 {@code checkCodeUnique} 生成的 {@code code = null}
     * 在 SQL 里恒不匹配，能建出一堆 code 为 null 的报表，唯一索引也拦不住）、拦住空白与
     * 斜杠引号这类会跑进地址栏和 SQL 里的字符。
     */
    private static final String CODE_PATTERN = "^[A-Za-z0-9_.\\-]{1,50}$";

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 报表的内部数据集随报表一起删除/复制，见 {@link #remove} / {@link #copy}。 */
    private final DatasetService datasetService;

    /**
     * 报表内容按**版本**存，本类只负责把 content 转交给它。
     *
     * <p>{@code mz_report.content} 这一列已废弃（CONTRACT §2 标了 deprecated），只在懒迁移时被读一次；
     * **不做双写** —— 双写必然漂移，违背契约优先。
     */
    private final ReportVersionService versionService;

    /** 只为了那个 {@code auto-create} 开关，见 {@link #getDetail(String, String, boolean)}。 */
    private final MzProperties props;

    @Override
    public PageResult<MzReport> page(long pageNo, long pageSize, String name) {
        LambdaQueryWrapper<MzReport> wrapper = new LambdaQueryWrapper<>();
        // 列表接口不需要（也不应该）传输大字段 content
        wrapper.select(MzReport.class, info -> !"content".equals(info.getColumn()));
        if (StringUtils.hasText(name)) {
            wrapper.like(MzReport::getName, name);
        }
        wrapper.orderByDesc(MzReport::getUpdateTime);
        Page<MzReport> pageParam = new Page<>(pageNo, pageSize);
        Page<MzReport> result = page(pageParam, wrapper);
        return PageResult.of(result);
    }

    @Override
    public MzReport getDetail(String id) {
        return getDetail(id, null);
    }

    @Override
    public MzReport getDetail(String id, String versionId) {
        return getDetail(id, versionId, true);
    }

    @Override
    public MzReport getDetail(String id, String versionId, boolean autoCreate) {
        if (!StringUtils.hasText(id)) {
            throw new BizException("报表 id 不能为空");
        }
        MzReport report = loadLite(id);
        if (report == null) {
            // 报表不存在就当场建一张空白的：外部系统拿自己的业务 KEY 直接打开设计器，
            // 不必先来一次 POST /api/report。id 同时当 code / name 用。
            // 只有设计器打开那条路走这里 —— 渲染/导出传的是 false，见 ReportService 上的注释；
            // 整个能力还能用 muzhou.report.auto-create 关掉
            if (!autoCreate || !props.isAutoCreate()) {
                throw new BizException("报表不存在: " + id);
            }
            report = autoCreate(id);
            // 新建出来的只有 v1，按 code 捡到的又是另一张报表 —— 两种情况下调用方带的
            // versionId 都不属于这张报表，拿它去查必然报「版本不存在或不属于该报表」，
            // 直接退回默认版本
            versionId = null;
        }
        // detail 内部会先做懒迁移（老报表一条版本行都没有时用它自己的 content 建 v1），
        // 这里不必再调一次 —— 多调一次就是每次打开报表多一条 count 查询
        MzReportVersion version = versionService.detail(report.getId(), versionId);
        // content 从这一版取，接口形态不变 —— 前端不必知道内容换了个存法
        report.setContent(version.getContent());
        report.setVersionId(version.getId());
        return report;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(MzReport report) {
        validate(report);
        checkCodeUnique(report.getCode(), null);
        if (report.getStatus() == null) {
            report.setStatus(1);
        }
        if (!StringUtils.hasText(report.getType())) {
            report.setType("sheet");
        }
        if (!StringUtils.hasText(report.getContent())) {
            report.setContent(emptyContent());
        }
        save(report);
        // 新报表也从 v1 起步：走的是同一条懒迁移（读 mz_report.content 建 v1），不另写一套
        versionService.ensureMigrated(report.getId());
        return report.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(MzReport report) {
        MzReport old = getById(report.getId());
        if (old == null) {
            throw new BizException("报表不存在");
        }
        validate(report);
        checkCodeUnique(report.getCode(), report.getId());
        if (report.getContent() != null) {
            // saveContent -> detail 里已经带了懒迁移；没带 content 的调用（报表列表页改个名字）
            // 压根不碰版本，也就不必为它多跑一次迁移检查
            // 版式写进 versionId 指定的那一版（省略 = 默认版本），**不回写 mz_report.content**
            versionService.saveContent(report.getId(), report.getVersionId(), report.getContent());
        }
        // 置空后 MyBatis-Plus 会跳过这一列（默认 NOT_NULL 策略），废弃列保持原样不动
        report.setContent(null);
        return updateById(report);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean remove(String id) {
        MzReport report = getById(id);
        if (report == null) {
            throw new BizException("报表不存在");
        }
        // 内部数据集是这张报表的一部分，跟着一起删；公共数据集不受影响
        datasetService.removeByReport(id);
        // 版式也是这张报表的一部分，各版本行一并逻辑删除
        versionService.removeByReport(id);
        return removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String copy(String id) {
        MzReport source = getById(id);
        if (source == null) {
            throw new BizException("报表不存在");
        }
        versionService.ensureMigrated(id);
        MzReport copy = new MzReport();
        copy.setName(source.getName() + "_副本");
        copy.setCode(uniqueCopyCode(source.getCode()));
        copy.setType(source.getType());
        // 版式在版本行里，不在这一列（废弃列不写）
        copy.setVersionConfig(source.getVersionConfig());
        copy.setRemark(source.getRemark());
        copy.setStatus(source.getStatus());
        copy.setCreateBy(source.getCreateBy());
        save(copy);
        // content 里的 #{code.字段} 原样复制过来了，内部数据集不跟着复制的话副本会取不到数
        datasetService.copyToReport(id, copy.getId());
        // **所有版本行一起复制**（保留 versionNo / effectiveFrom / status / isDefault）——
        // 漏了这条，副本会是一张没有版式的报表
        versionService.copyToReport(id, copy.getId());
        return copy.getId();
    }

    /**
     * 报表不存在时按 id 建一张空白报表并返回。
     *
     * <p>建之前先按 code 找一遍：外部系统给的这个 KEY 可能早就在报表管理页上建过
     * （那时 id 是 UUID、code 才是这个 KEY），照建必然撞 {@code uk_report_code}。
     *
     * <p>注意 {@code uk_report_code} / 主键索引都**不带 deleted 条件**，而
     * {@link #checkCodeUnique} 和这里的查询只看未删除的行 —— 撞库时靠 catch 兜底区分
     * 「并发建」和「同 id/code 的行已被逻辑删除」。
     */
    private MzReport autoCreate(String id) {
        MzReport exist = loadLiteByCode(id);
        if (exist != null) {
            return exist;
        }
        // id/code/name 三列分别是 varchar(32)/(50)/(100)，这里三者同值，卡最短的那个，
        // 免得直接抛一条看不懂的 SQL 异常
        if (id.length() > 32) {
            throw new BizException("报表 id 超长（最多 32 位），无法自动创建: " + id);
        }
        // 先自己判一次：id 要同时当 code 用，不合规的话下面 create 会抛校验异常，
        // 而那个异常正好落进 catch 里被当成「撞了唯一索引」，最后报出来的是
        // 「已被删除，无法自动创建」—— 一句完全对不上的话
        if (!id.matches(CODE_PATTERN)) {
            throw new BizException("报表 id 只能包含字母、数字、下划线、中划线、点，无法自动创建: " + id);
        }
        MzReport report = new MzReport();
        report.setId(id);
        report.setCode(id);
        report.setName(id);
        try {
            // 同类内调用不走事务代理，save 与建 v1 不在一个事务里；万一版本行没建成也不用管，
            // 下面 versionService.detail 里的懒迁移会补上
            create(report);
        } catch (DuplicateKeyException | BizException e) {
            // 两个请求同时打开同一张还不存在的报表：谁先谁算，慢的那个重新读一次
            MzReport now = loadLite(id);
            if (now == null) {
                now = loadLiteByCode(id);
            }
            if (now == null) {
                // 未删除的行里找不到，却又撞了唯一索引 —— 这个 id/code 被逻辑删除过
                throw new BizException("报表[" + id + "]已被删除，无法自动创建");
            }
            return now;
        }
        log.info("报表[{}]不存在，已自动创建空白报表", id);
        return report;
    }

    private MzReport loadLite(String id) {
        return getOne(liteWrapper().eq(MzReport::getId, id), false);
    }

    private MzReport loadLiteByCode(String code) {
        return getOne(liteWrapper().eq(MzReport::getCode, code), false);
    }

    /**
     * 读报表本身时**不带 content**：这一列已废弃且是 CLOB，返给前端的 content 一律来自版本行
     * （{@link #getDetail} 里紧接着就被覆盖）—— 详情/渲染每次都白读一份大字段。
     */
    private LambdaQueryWrapper<MzReport> liteWrapper() {
        LambdaQueryWrapper<MzReport> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(MzReport.class, info -> !"content".equals(info.getColumn()));
        return wrapper;
    }

    private void validate(MzReport report) {
        if (!StringUtils.hasText(report.getName())) {
            throw new BizException("报表名称不能为空");
        }
        if (!StringUtils.hasText(report.getCode()) || !report.getCode().matches(CODE_PATTERN)) {
            throw new BizException("报表编码不能为空，且只能包含字母、数字、下划线、中划线、点（最多 50 位）");
        }
    }

    private void checkCodeUnique(String code, String excludeId) {
        LambdaQueryWrapper<MzReport> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MzReport::getCode, code);
        if (StringUtils.hasText(excludeId)) {
            wrapper.ne(MzReport::getId, excludeId);
        }
        if (count(wrapper) > 0) {
            throw new BizException("报表编码已存在: " + code);
        }
    }

    /** 复制时 code 加 _copy_时间戳 后缀；万一同一秒内重复复制导致后缀撞车，再追加序号保证唯一。 */
    private String uniqueCopyCode(String code) {
        String suffix = "_copy_" + LocalDateTime.now().format(TS_FORMAT);
        String candidate = code + suffix;
        int seq = 1;
        while (count(new LambdaQueryWrapper<MzReport>().eq(MzReport::getCode, candidate)) > 0) {
            candidate = code + suffix + "_" + (seq++);
        }
        return candidate;
    }

    /** 新建报表未指定 content 时的默认空白单页内容。 */
    private String emptyContent() {
        return "{\"version\":1,\"sheets\":[{\"name\":\"Sheet1\",\"id\":\"sheet_1\",\"order\":0,\"status\":1,"
                + "\"row\":60,\"column\":20,\"celldata\":[],\"config\":{}}],\"cellConfigs\":{},\"params\":[],"
                + "\"pageConfig\":{\"paperSize\":\"A4\",\"orientation\":\"portrait\",\"marginTop\":10,"
                + "\"marginBottom\":10,\"marginLeft\":10,\"marginRight\":10}}";
    }
}
