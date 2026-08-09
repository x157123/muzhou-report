package com.muzhou.report.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muzhou.report.common.BizException;
import com.muzhou.report.common.PageResult;
import com.muzhou.report.dto.ReportParamDTO;
import com.muzhou.report.entity.MzParam;
import com.muzhou.report.mapper.MzParamMapper;
import com.muzhou.report.service.ParamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全局参数管理服务实现。见 docs/CONTRACT.md §3.5。
 */
@Slf4j
@Service
public class ParamServiceImpl extends ServiceImpl<MzParamMapper, MzParam> implements ParamService {

    /** 参数名规则：字母或下划线开头，仅含字母、数字、下划线。与设计器里那份校验同一条。 */
    private static final String NAME_PATTERN = "^[a-zA-Z_][a-zA-Z0-9_]*$";

    private final ObjectMapper objectMapper;

    /**
     * 手写构造器而非 {@code @RequiredArgsConstructor}：容器里有两个 {@link ObjectMapper}，
     * 必须显式 {@link Qualifier}，而 Lombok 不会把字段上的注解搬到构造器参数上
     * （同 {@link RenderServiceImpl}）。
     */
    public ParamServiceImpl(@Qualifier("mzObjectMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResult<MzParam> page(long pageNo, long pageSize, String name) {
        LambdaQueryWrapper<MzParam> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(name)) {
            // 参数名和显示名都能搜到：用户记得住「部门」的多过记得住 deptId
            wrapper.and(w -> w.like(MzParam::getParamName, name).or().like(MzParam::getParamText, name));
        }
        wrapper.orderByAsc(MzParam::getParamName);
        return PageResult.of(page(new Page<>(pageNo, pageSize), wrapper));
    }

    @Override
    public List<MzParam> listAll() {
        LambdaQueryWrapper<MzParam> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MzParam::getStatus, 1);
        wrapper.orderByAsc(MzParam::getParamName);
        return list(wrapper);
    }

    @Override
    public MzParam get(String id) {
        MzParam param = getById(id);
        if (param == null) {
            throw new BizException("参数不存在");
        }
        return param;
    }

    @Override
    public String create(MzParam param) {
        checkName(param.getParamName(), null);
        fillDefaults(param);
        // 唯一索引不带 deleted 条件：删掉之后再建同名的会撞唯一键，先把那具尸体清掉
        baseMapper.purgeDeletedByName(param.getParamName());
        save(param);
        return param.getId();
    }

    @Override
    public boolean update(MzParam param) {
        MzParam old = getById(param.getId());
        if (old == null) {
            throw new BizException("参数不存在");
        }
        checkName(param.getParamName(), param.getId());
        fillDefaults(param);
        if (!param.getParamName().equals(old.getParamName())) {
            // 改名等于占用一个新名字，同样要躲开已删除的同名行
            baseMapper.purgeDeletedByName(param.getParamName());
        }
        return updateById(param);
    }

    @Override
    public boolean remove(String id) {
        return removeById(id);
    }

    @Override
    public List<ReportParamDTO> listDefinitions() {
        List<ReportParamDTO> defs = new ArrayList<>();
        for (MzParam p : listAll()) {
            defs.add(toDefinition(p));
        }
        return defs;
    }

    /**
     * 实体 -> 引擎认的参数定义。列名 {@code param_name/param_text/param_type} 在这里改回
     * {@code name/text/type}（见 {@link MzParam} 的类注释）。
     */
    private ReportParamDTO toDefinition(MzParam p) {
        ReportParamDTO def = new ReportParamDTO();
        def.setName(p.getParamName());
        def.setText(p.getParamText());
        def.setType(StringUtils.hasText(p.getParamType()) ? p.getParamType() : "string");
        def.setWidget(StringUtils.hasText(p.getWidget()) ? p.getWidget() : "input");
        def.setDefaultValue(p.getDefaultValue() == null ? "" : p.getDefaultValue());
        def.setRequired(Integer.valueOf(1).equals(p.getRequired()));
        def.setOptions(parseOptions(p));
        return def;
    }

    /**
     * 下拉选项存的是 JSON 数组文本。存坏了只让这一个参数没有选项，不拖垮整次渲染 ——
     * 参数表单里少一组候选值，比整张报表打不开好收场。
     */
    private List<Map<String, Object>> parseOptions(MzParam p) {
        if (!StringUtils.hasText(p.getOptions())) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(p.getOptions(), new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            log.warn("全局参数[{}]的选项不是合法 JSON，按无选项处理: {}", p.getParamName(), p.getOptions());
            return new ArrayList<>();
        }
    }

    /** 参数名要进 SQL 的 {@code ${}} 与接口地址，名字随便起会在取数那头才炸，所以在入口拦。 */
    private void checkName(String name, String selfId) {
        if (!StringUtils.hasText(name) || !name.matches(NAME_PATTERN)) {
            throw new BizException("参数名必须以字母或下划线开头，只能包含字母、数字、下划线");
        }
        LambdaQueryWrapper<MzParam> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MzParam::getParamName, name);
        if (StringUtils.hasText(selfId)) {
            wrapper.ne(MzParam::getId, selfId);
        }
        if (count(wrapper) > 0) {
            throw new BizException("参数名已存在: " + name);
        }
    }

    private void fillDefaults(MzParam param) {
        if (!StringUtils.hasText(param.getParamType())) {
            param.setParamType("string");
        }
        if (!StringUtils.hasText(param.getWidget())) {
            param.setWidget("input");
        }
        if (param.getRequired() == null) {
            param.setRequired(0);
        }
        if (param.getStatus() == null) {
            param.setStatus(1);
        }
    }
}
