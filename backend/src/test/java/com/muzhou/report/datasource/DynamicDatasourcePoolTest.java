package com.muzhou.report.datasource;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.dynamic.datasource.creator.DefaultDataSourceCreator;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariCpConfig;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariDataSourceCreator;
import com.baomidou.dynamic.datasource.ds.ItemDataSource;
import com.baomidou.dynamic.datasource.event.DataSourceInitEvent;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.entity.MzDatasource;
import com.muzhou.report.service.DatasourceService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DynamicDatasourceRegistry} 的连接池行为测试（纯 POJO，不起 Spring 上下文，
 * 用内存 H2 当业务库）。锁三条：
 *
 * <p>① 「数据源管理」里加出来的业务库拿到的是一个 <b>HikariCP 池</b>，不是裸连接；
 * ② 池参数来自 {@code muzhou.report.pool.*}，<b>不会</b>被 master 那份全局配置盖掉
 * （对应 {@code poolConfig()} 里「一项不留 null」那条注释 —— 留 null 就会被 merge 成全局值）；
 * ③ 池是<b>定长</b>的：借满之后第三个请求排队等 {@code connection-timeout} 然后报错，
 * 而不是再往库上开一条新连接。
 *
 * <p>外加一条并发的：同一个 code 同时被多个线程首次用到时只建一个池。
 */
class DynamicDatasourcePoolTest {

    /** 冒充 master 那份 {@code spring.datasource.dynamic.hikari}：这里的值一个都不该出现在业务库池上。 */
    private static final int MASTER_MAX_POOL_SIZE = 99;

    private final MzProperties props = new MzProperties();

    private DynamicRoutingDataSource routing;

    private DatasourceService datasourceService;

    private DynamicDatasourceRegistry registry;

    @BeforeEach
    void setUp() {
        routing = new DynamicRoutingDataSource(new ArrayList<>());

        HikariCpConfig masterConfig = new HikariCpConfig();
        masterConfig.setMaximumPoolSize(MASTER_MAX_POOL_SIZE);
        masterConfig.setMinimumIdle(MASTER_MAX_POOL_SIZE);
        masterConfig.setConnectionTimeout(11111L);

        DefaultDataSourceCreator creator = new DefaultDataSourceCreator();
        creator.setCreators(List.of(new HikariDataSourceCreator(masterConfig)));
        creator.setDataSourceInitEvent(new DataSourceInitEvent() {
            @Override
            public void beforeCreate(DataSourceProperty dataSourceProperty) {
                // 测试里不需要
            }

            @Override
            public void afterCreate(DataSource dataSource) {
                // 测试里不需要
            }
        });

        datasourceService = mock(DatasourceService.class);
        registry = new DynamicDatasourceRegistry(routing, creator, props, provider(datasourceService));
    }

    @AfterEach
    void tearDown() {
        // 每个池都带着自己的线程和连接，跑完要关掉
        new ArrayList<>(routing.getDataSources().keySet()).forEach(registry::remove);
    }

    @Test
    @DisplayName("业务库注册出来的是 HikariCP 池，池参数取自 muzhou.report.pool")
    void businessDatasourceGetsAHikariPoolConfiguredByMzProperties() {
        MzProperties.Pool pool = props.getPool();
        pool.setMaximumPoolSize(7);
        pool.setMinimumIdle(2);
        pool.setConnectionTimeout(4321L);
        pool.setIdleTimeout(60000L);
        pool.setMaxLifetime(120000L);

        registry.register(h2("bizpool"));
        HikariDataSource hikari = hikariOf(registry.getOrRegister("bizpool"));

        assertEquals("bizpool", hikari.getPoolName());
        // 取的是 muzhou.report.pool 那份，不是上面那份冒充 master 的全局配置
        assertEquals(7, hikari.getMaximumPoolSize());
        assertEquals(2, hikari.getMinimumIdle());
        assertEquals(4321L, hikari.getConnectionTimeout());
        assertEquals(60000L, hikari.getIdleTimeout());
        assertEquals(120000L, hikari.getMaxLifetime());
    }

    @Test
    @DisplayName("池满时排队等超时，而不是再往库上开一条连接")
    void poolIsFixedSizeAndQueuesInsteadOfOpeningMoreConnections() throws Exception {
        MzProperties.Pool pool = props.getPool();
        pool.setMaximumPoolSize(2);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(600L);
        pool.setValidationTimeout(250L);

        registry.register(h2("bizfixed"));
        DataSource ds = registry.getOrRegister("bizfixed");

        try (Connection first = ds.getConnection(); Connection second = ds.getConnection()) {
            assertNotNull(first);
            assertNotNull(second);
            assertEquals(2, hikariOf(ds).getHikariPoolMXBean().getTotalConnections());

            long start = System.currentTimeMillis();
            assertThrows(SQLTransientConnectionException.class, ds::getConnection,
                    "池满了应该排队等超时后报错");
            long waited = System.currentTimeMillis() - start;

            assertTrue(waited >= 400, "应该是等满 connection-timeout 才报错，实际只等了 " + waited + "ms");
            assertEquals(2, hikariOf(ds).getHikariPoolMXBean().getTotalConnections(),
                    "等超时也不该多开一条连接");
        }
    }

    @Test
    @DisplayName("同一个 code 并发首次取用只建一个池")
    void concurrentFirstUseBuildsOnlyOnePool() throws Exception {
        props.getPool().setMaximumPoolSize(3);
        props.getPool().setMinimumIdle(1);
        when(datasourceService.getByCode("bizrace")).thenReturn(h2("bizrace"));

        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<DataSource> seen = Collections.newSetFromMap(new ConcurrentHashMap<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        gate.await();
                        seen.add(registry.getOrRegister("bizrace"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            gate.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发取用超时");
        } finally {
            pool.shutdownNow();
        }

        // 没有锁的话每个线程都会各查一次库、各建一个池，后建的把先建的顶掉
        verify(datasourceService, times(1)).getByCode("bizrace");
        assertEquals(1, seen.size(), "所有线程应该拿到同一个 DataSource 实例");
        assertEquals(1, routing.getDataSources().size());
    }

    /* ----------------------------- 内部工具 ----------------------------- */

    /** dynamic-datasource 会把真正的池包一层 {@link ItemDataSource}，剥掉再看。 */
    private HikariDataSource hikariOf(DataSource ds) {
        DataSource real = ds instanceof ItemDataSource item ? item.getRealDataSource() : ds;
        assertInstanceOf(HikariDataSource.class, real,
                "业务库必须落到 HikariCP 池上，实际是 " + real.getClass().getName());
        return (HikariDataSource) real;
    }

    private MzDatasource h2(String code) {
        MzDatasource ds = new MzDatasource();
        ds.setCode(code);
        ds.setName(code);
        ds.setDbType("h2");
        // DB_CLOSE_DELAY=-1：池把连接全还回去之后内存库也别消失
        ds.setUrl("jdbc:h2:mem:" + code + ";DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        return ds;
    }

    private ObjectProvider<DatasourceService> provider(DatasourceService service) {
        return new ObjectProvider<>() {
            @Override
            public DatasourceService getObject() {
                return service;
            }

            @Override
            public DatasourceService getObject(Object... args) {
                return service;
            }

            @Override
            public DatasourceService getIfAvailable() {
                return service;
            }

            @Override
            public DatasourceService getIfUnique() {
                return service;
            }
        };
    }
}
