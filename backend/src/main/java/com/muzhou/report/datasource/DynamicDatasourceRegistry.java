package com.muzhou.report.datasource;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.creator.DefaultDataSourceCreator;
import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.muzhou.report.common.BizException;
import com.muzhou.report.entity.MzDatasource;
import com.muzhou.report.service.DatasourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 动态数据源运行时注册中心。
 * 基于 dynamic-datasource 4.3.1 的 {@link DynamicRoutingDataSource} API，在运行时增删数据源，
 * 支持"惰性加载"：启动时不主动连接任何业务库，首次使用时才从 mz_datasource 表读取配置并注册，
 * 避免某个数据源连不上导致整个应用启动失败。
 */
@Slf4j
@Component
public class DynamicDatasourceRegistry {

    /** 实际运行时类型是 DynamicRoutingDataSource，这里按接口注入，使用时强转。 */
    private final DataSource dataSource;

    private final DefaultDataSourceCreator dataSourceCreator;

    /**
     * 用 ObjectProvider 延迟获取 DatasourceService，避免 Service -> Registry -> Service 的循环依赖
     * （DatasourceServiceImpl 持有 Registry 用于 test/remove，而 Registry 惰性注册时又需要反查 Service）。
     */
    private final ObjectProvider<DatasourceService> datasourceServiceProvider;

    public DynamicDatasourceRegistry(DataSource dataSource,
                                      DefaultDataSourceCreator dataSourceCreator,
                                      ObjectProvider<DatasourceService> datasourceServiceProvider) {
        this.dataSource = dataSource;
        this.dataSourceCreator = dataSourceCreator;
        this.datasourceServiceProvider = datasourceServiceProvider;
    }

    private DynamicRoutingDataSource routing() {
        return (DynamicRoutingDataSource) dataSource;
    }

    /** 是否已注册。 */
    public boolean exists(String code) {
        if (code == null) {
            return false;
        }
        return routing().getDataSources().containsKey(code);
    }

    /**
     * 注册（幂等）：若已存在同 key 的数据源先移除再新增，保证使用最新的连接信息。
     */
    public void register(MzDatasource ds) {
        if (ds == null || ds.getCode() == null) {
            throw new BizException("数据源配置不完整，无法注册");
        }
        if (exists(ds.getCode())) {
            remove(ds.getCode());
        }
        DataSourceProperty prop = toProperty(ds);
        DataSource created = dataSourceCreator.createDataSource(prop);
        routing().addDataSource(ds.getCode(), created);
        log.info("动态数据源已注册: {}", ds.getCode());
    }

    /** 从路由数据源中移除（不影响数据库中的配置记录）。 */
    public void remove(String code) {
        if (code != null && exists(code)) {
            routing().removeDataSource(code);
            log.info("动态数据源已移除: {}", code);
        }
    }

    /**
     * 获取指定 code 对应的 DataSource，若尚未注册则惰性从数据库加载配置并注册。
     */
    public DataSource getOrRegister(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException("数据源编码不能为空");
        }
        // master 是 dynamic-datasource 的默认主库 key，直接放行
        if ("master".equals(code) || exists(code)) {
            return routing().getDataSource(code);
        }
        DatasourceService service = datasourceServiceProvider.getIfAvailable();
        if (service == null) {
            throw new BizException("数据源服务不可用: " + code);
        }
        MzDatasource ds = service.getByCode(code);
        if (ds == null) {
            throw new BizException("数据源不存在: " + code);
        }
        register(ds);
        return routing().getDataSource(code);
    }

    /**
     * 直接试连（不进连接池），用于"测试连接"功能。失败抛出 BizException 携带原因。
     */
    public boolean test(MzDatasource ds) {
        String driver = resolveDriver(ds);
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new BizException("驱动类未找到: " + driver);
        }
        try (Connection ignored = DriverManager.getConnection(ds.getUrl(), ds.getUsername(), ds.getPassword())) {
            return true;
        } catch (SQLException e) {
            throw new BizException("连接失败: " + e.getMessage());
        }
    }

    private DataSourceProperty toProperty(MzDatasource ds) {
        DataSourceProperty prop = new DataSourceProperty();
        prop.setPoolName(ds.getCode());
        prop.setDriverClassName(resolveDriver(ds));
        prop.setUrl(ds.getUrl());
        prop.setUsername(ds.getUsername());
        prop.setPassword(ds.getPassword());
        return prop;
    }

    private String resolveDriver(MzDatasource ds) {
        if (ds.getDriverClassName() != null && !ds.getDriverClassName().isBlank()) {
            return ds.getDriverClassName();
        }
        return DbTypeEnum.of(ds.getDbType()).getDriverClassName();
    }
}
