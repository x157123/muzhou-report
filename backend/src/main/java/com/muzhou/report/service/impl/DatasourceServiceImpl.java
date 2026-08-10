package com.muzhou.report.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.muzhou.report.common.BizException;
import com.muzhou.report.common.PageResult;
import com.muzhou.report.datasource.DbTypeEnum;
import com.muzhou.report.datasource.DynamicDatasourceRegistry;
import com.muzhou.report.datasource.JdbcExecutor;
import com.muzhou.report.dto.ColumnMetaDTO;
import com.muzhou.report.entity.MzDataset;
import com.muzhou.report.entity.MzDatasource;
import com.muzhou.report.mapper.MzDatasetMapper;
import com.muzhou.report.mapper.MzDatasourceMapper;
import com.muzhou.report.service.DatasourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 数据源管理服务实现。见 docs/CONTRACT.md §3.1。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceServiceImpl extends ServiceImpl<MzDatasourceMapper, MzDatasource> implements DatasourceService {

    /** 数据源编码规则：字母开头，仅含字母、数字、下划线。 */
    private static final String CODE_PATTERN = "^[a-zA-Z][a-zA-Z0-9_]*$";

    private final DynamicDatasourceRegistry registry;

    private final JdbcExecutor jdbcExecutor;

    private final MzDatasetMapper mzDatasetMapper;

    @Override
    public PageResult<MzDatasource> page(long pageNo, long pageSize, String name) {
        LambdaQueryWrapper<MzDatasource> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(name)) {
            wrapper.like(MzDatasource::getName, name);
        }
        wrapper.orderByDesc(MzDatasource::getCreateTime);
        Page<MzDatasource> pageParam = new Page<>(pageNo, pageSize);
        Page<MzDatasource> result = page(pageParam, wrapper);
        return PageResult.of(result);
    }

    @Override
    public List<MzDatasource> listAll() {
        LambdaQueryWrapper<MzDatasource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MzDatasource::getStatus, 1);
        wrapper.orderByAsc(MzDatasource::getName);
        return list(wrapper);
    }

    @Override
    public String create(MzDatasource ds) {
        if (!StringUtils.hasText(ds.getName())) {
            throw new BizException("数据源名称不能为空");
        }
        if (!StringUtils.hasText(ds.getCode()) || !ds.getCode().matches(CODE_PATTERN)) {
            throw new BizException("数据源编码必须以字母开头，只能包含字母、数字、下划线");
        }
        checkCodeUnique(ds.getCode(), null);
        fillDriver(ds);
        if (ds.getStatus() == null) {
            ds.setStatus(1);
        }
        save(ds);
        return ds.getId();
    }

    @Override
    public boolean update(MzDatasource ds) {
        MzDatasource old = getById(ds.getId());
        if (old == null) {
            throw new BizException("数据源不存在");
        }
        if (!StringUtils.hasText(ds.getName())) {
            throw new BizException("数据源名称不能为空");
        }
        if (!StringUtils.hasText(ds.getCode()) || !ds.getCode().matches(CODE_PATTERN)) {
            throw new BizException("数据源编码必须以字母开头，只能包含字母、数字、下划线");
        }
        checkCodeUnique(ds.getCode(), ds.getId());
        fillDriver(ds);
        keepOldPassword(ds, old);
        boolean connectionChanged = !Objects.equals(old.getCode(), ds.getCode())
                || !Objects.equals(old.getUrl(), ds.getUrl())
                || !Objects.equals(old.getUsername(), ds.getUsername())
                || !Objects.equals(old.getPassword(), ds.getPassword())
                || !Objects.equals(old.getDriverClassName(), ds.getDriverClassName());
        if (connectionChanged) {
            // 连接信息发生变化，移除旧的运行时注册，下次使用时惰性重新注册
            registry.remove(old.getCode());
        }
        return updateById(ds);
    }

    @Override
    public boolean remove(String id) {
        MzDatasource ds = getById(id);
        if (ds == null) {
            throw new BizException("数据源不存在");
        }
        long refCount = mzDatasetMapper.selectCount(
                new LambdaQueryWrapper<MzDataset>().eq(MzDataset::getDatasourceId, id));
        if (refCount > 0) {
            throw new BizException("该数据源已被 " + refCount + " 个数据集使用，无法删除");
        }
        registry.remove(ds.getCode());
        return removeById(id);
    }

    @Override
    public boolean test(MzDatasource ds) {
        fillDriver(ds);
        // 「测试连接」按的是编辑弹窗里那一份，编辑已有数据源时口令多半是空的（前端拿不到原值，
        // 见 MzDatasource#password），这时要拿库里存着的那个去试，否则永远报「连接失败」
        if (StringUtils.hasText(ds.getId())) {
            keepOldPassword(ds, getById(ds.getId()));
        }
        return registry.test(ds);
    }

    /**
     * 提交上来的口令为空 = 不修改，沿用库里存着的那个。
     *
     * <p>口令是 {@code WRITE_ONLY} 的（见 {@link MzDatasource#getPassword()} 上的注释），
     * 前端编辑时拿不到原值、也就不会把它带回来。不做这一步的话，用户在界面上改一下备注，
     * 数据源的口令就被清空了 —— 而且要等到下次取数才发现。
     */
    private void keepOldPassword(MzDatasource ds, MzDatasource old) {
        if (old != null && !StringUtils.hasText(ds.getPassword())) {
            ds.setPassword(old.getPassword());
        }
    }

    @Override
    public List<String> tables(String id) {
        MzDatasource ds = getById(id);
        if (ds == null) {
            throw new BizException("数据源不存在");
        }
        return jdbcExecutor.listTables(ds.getCode());
    }

    @Override
    public List<ColumnMetaDTO> columns(String id, String table) {
        MzDatasource ds = getById(id);
        if (ds == null) {
            throw new BizException("数据源不存在");
        }
        return jdbcExecutor.listColumns(ds.getCode(), table);
    }

    @Override
    public MzDatasource getByCode(String code) {
        return lambdaQuery().eq(MzDatasource::getCode, code).one();
    }

    /**
     * 校验 code 唯一性，excludeId 不为空时排除自身（用于更新场景）。
     */
    private void checkCodeUnique(String code, String excludeId) {
        LambdaQueryWrapper<MzDatasource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MzDatasource::getCode, code);
        if (StringUtils.hasText(excludeId)) {
            wrapper.ne(MzDatasource::getId, excludeId);
        }
        if (count(wrapper) > 0) {
            throw new BizException("数据源编码已存在: " + code);
        }
    }

    /**
     * driverClassName 为空时，按 dbType 自动补齐默认驱动类名。
     */
    private void fillDriver(MzDatasource ds) {
        if (!StringUtils.hasText(ds.getDriverClassName())) {
            ds.setDriverClassName(DbTypeEnum.of(ds.getDbType()).getDriverClassName());
        }
    }
}
