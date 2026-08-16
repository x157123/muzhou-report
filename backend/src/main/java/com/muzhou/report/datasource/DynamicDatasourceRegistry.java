package com.muzhou.report.datasource;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.creator.DefaultDataSourceCreator;
import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariCpConfig;
import com.muzhou.report.common.BizException;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.entity.MzDatasource;
import com.muzhou.report.service.DatasourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态数据源运行时注册中心。
 * 基于 dynamic-datasource 4.3.1 的 {@link DynamicRoutingDataSource} API，在运行时增删数据源，
 * 支持"惰性加载"：启动时不主动连接任何业务库，首次使用时才从 mz_datasource 表读取配置并注册，
 * 避免某个数据源连不上导致整个应用启动失败。
 *
 * <p><b>每个业务库一个定长的 HikariCP 连接池，参数一律来自 {@code muzhou.report.pool.*}</b>
 * （见 {@link MzProperties.Pool}）：查询是从池里借连接、用完还回去，池满则排队等
 * {@code connection-timeout}，**不会再往数据库上开新连接**。
 */
@Slf4j
@Component
public class DynamicDatasourceRegistry {

    /** 实际运行时类型是 DynamicRoutingDataSource，这里按接口注入，使用时强转。 */
    private final DataSource dataSource;

    private final DefaultDataSourceCreator dataSourceCreator;

    private final MzProperties props;

    /**
     * 用 ObjectProvider 延迟获取 DatasourceService，避免 Service -> Registry -> Service 的循环依赖
     * （DatasourceServiceImpl 持有 Registry 用于 test/remove，而 Registry 惰性注册时又需要反查 Service）。
     */
    private final ObjectProvider<DatasourceService> datasourceServiceProvider;

    /** 惰性建池时按 code 串行的锁，见 {@link #getOrRegister(String)}。条目数 = 数据源个数，不会涨。 */
    private final Map<String, Object> registerLocks = new ConcurrentHashMap<>();

    public DynamicDatasourceRegistry(DataSource dataSource,
                                      DefaultDataSourceCreator dataSourceCreator,
                                      MzProperties props,
                                      ObjectProvider<DatasourceService> datasourceServiceProvider) {
        this.dataSource = dataSource;
        this.dataSourceCreator = dataSourceCreator;
        this.props = props;
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
     * 注册（幂等）：同 key 已存在时用新池顶替，旧池由 dynamic-datasource 关掉
     * （配了 {@code spring.datasource.dynamic.grace-destroy: true} 则等它上面的连接用完再关）。
     *
     * <p><b>不先 remove 再 add</b>：那样会留出一个「这个 code 不存在」的窗口，正好落在窗口里的
     * 渲染会报「找不到数据源」，而且旧池被立刻关掉，别的线程手上正在跑的查询跟着断。
     */
    public void register(MzDatasource ds) {
        if (ds == null || ds.getCode() == null) {
            throw new BizException("数据源配置不完整，无法注册");
        }
        DataSourceProperty prop = toProperty(ds);
        DataSource created;
        try {
            created = dataSourceCreator.createDataSource(prop);
        } catch (Exception e) {
            // 建池失败（连不上、驱动缺失、口令错）时这个 code 仍然是「未注册」，下次用到会重试。
            // 不包一层的话抛出去的是 HikariPool.PoolInitializationException，前端只看到一串英文
            log.error("动态数据源建池失败: {}", ds.getCode(), e);
            throw new BizException("数据源连接池创建失败(" + ds.getCode() + "): " + rootMessage(e), e);
        }
        routing().addDataSource(ds.getCode(), created);
        log.info("动态数据源已注册: {}（连接池上限 {}，常驻空闲 {}）",
                ds.getCode(), props.getPool().getMaximumPoolSize(), props.getPool().getMinimumIdle());
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
     *
     * <p><b>惰性建池必须按 code 串起来</b>：并发的两次渲染同时发现某个 code 还没注册时，
     * 会各建一个池 —— 后建的把先建的顶掉，先建那个已经开出去的连接白开一遍（{@code minimumIdle}
     * 有几条就是几条），而先到的那个线程还可能刚好拿到正在被关掉的池。「明明只配了 10 条上限，
     * 库那边却看到二十几条连接」多半是这么来的。
     */
    public DataSource getOrRegister(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException("数据源编码不能为空");
        }
        // master 是 dynamic-datasource 的默认主库 key，直接放行
        if ("master".equals(code) || exists(code)) {
            return routing().getDataSource(code);
        }
        synchronized (registerLocks.computeIfAbsent(code, k -> new Object())) {
            // 双重检查：等锁的那段时间里前一个线程已经把池建好了
            if (!exists(code)) {
                DatasourceService service = datasourceServiceProvider.getIfAvailable();
                if (service == null) {
                    throw new BizException("数据源服务不可用: " + code);
                }
                MzDatasource ds = service.getByCode(code);
                if (ds == null) {
                    throw new BizException("数据源不存在: " + code);
                }
                register(ds);
            }
        }
        return routing().getDataSource(code);
    }

    /**
     * 直接试连（不进连接池），用于"测试连接"功能。失败抛出 BizException 携带原因。
     *
     * <p>刻意不建池：这一下试的是「这份连接信息对不对」，为它开一个池再关掉，
     * 池的最小空闲连接会白开几条，grace-destroy 下还要等着才关得掉。
     */
    public boolean test(MzDatasource ds) {
        String driver = resolveDriver(ds);
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new BizException("驱动类未找到: " + driver);
        }
        // DriverManager 的登录超时默认是 0 = 一直等：地址填成一个不通的内网 IP 时，
        // 「测试连接」会把这个请求线程挂在那儿直到 TCP 自己超时（分钟级）。借用池的
        // connection-timeout 当上限。这一项是 JVM 全局的，但只作用于 DriverManager 这条路
        // —— Hikari 走的是自己那份 loginTimeout，不受影响
        DriverManager.setLoginTimeout(Math.max(1, (int) (props.getPool().getConnectionTimeout() / 1000)));
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
        prop.setHikari(poolConfig());
        return prop;
    }

    /**
     * 把 {@code muzhou.report.pool.*} 翻译成 HikariCP 的池参数，**逐项都写、一项不留 null**。
     *
     * <p>留 null 的项会被 dynamic-datasource 拿 {@code spring.datasource.dynamic.hikari} 补上
     * （{@code HikariDataSourceCreator} 里那次 merge），而那一份配的是 master 元数据库的池 ——
     * 混着用就成了「改了元数据库的池，所有业务库跟着变」，两边说不清谁管谁。
     */
    private HikariCpConfig poolConfig() {
        MzProperties.Pool p = props.getPool();
        HikariCpConfig cfg = new HikariCpConfig();
        cfg.setMaximumPoolSize(p.getMaximumPoolSize());
        cfg.setMinimumIdle(p.getMinimumIdle());
        cfg.setConnectionTimeout(p.getConnectionTimeout());
        cfg.setValidationTimeout(p.getValidationTimeout());
        cfg.setIdleTimeout(p.getIdleTimeout());
        cfg.setMaxLifetime(p.getMaxLifetime());
        cfg.setKeepaliveTime(p.getKeepaliveTime());
        cfg.setLeakDetectionThreshold(p.getLeakDetectionThreshold());
        // 留空是常态：不设这一项 HikariCP 就走 JDBC4 的 isValid()，各家数据库通吃。
        // 设死一句 SELECT 1 的话 Oracle 上每次校验都是语法错（它要 SELECT 1 FROM DUAL）
        if (StringUtils.hasText(p.getConnectionTestQuery())) {
            cfg.setConnectionTestQuery(p.getConnectionTestQuery());
        }
        return cfg;
    }

    private String resolveDriver(MzDatasource ds) {
        if (ds.getDriverClassName() != null && !ds.getDriverClassName().isBlank()) {
            return ds.getDriverClassName();
        }
        return DbTypeEnum.of(ds.getDbType()).getDriverClassName();
    }

    /** 建池失败时最里层那条原因才说得清是什么问题（Hikari 会把 SQLException 包两层）。 */
    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
