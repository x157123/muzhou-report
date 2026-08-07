package com.muzhou.report.datasource;

import com.muzhou.report.common.BizException;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.ColumnMetaDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.Reader;
import java.io.StringWriter;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务库 JDBC 执行器。
 *
 * <p>刻意使用原生 JDBC 而非 JdbcTemplate，因为需要精确控制 {@code maxRows} / {@code queryTimeout} /
 * {@code fetchSize}——报表场景下一条写错的 SQL 很容易把整个应用拖垮。
 *
 * <p>约定：结果 Map 的 key 统一为 <b>小写列标签</b>，与 {@code mz_dataset_field.field_name} 保持一致，
 * 这样渲染引擎里的 {@code #{ds.field}} 才能稳定取到值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JdbcExecutor {

    private final DynamicDatasourceRegistry registry;
    private final MzProperties props;

    /**
     * 执行查询。
     *
     * @param maxRows 最大返回行数，<=0 时取全局配置
     */
    public List<Map<String, Object>> query(String dsCode, String sql, List<Object> args, int maxRows) {
        int limit = maxRows > 0 ? maxRows : props.getMaxRows();
        DataSource ds = registry.getOrRegister(dsCode);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            applyLimits(ps, limit);
            bindArgs(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                String[] labels = readLabels(md);
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < labels.length; i++) {
                        row.put(labels[i], convert(rs.getObject(i + 1)));
                    }
                    rows.add(row);
                }
            }
            return rows;
        } catch (SQLException e) {
            log.error("SQL 执行失败 dsCode={} sql={} args={}", dsCode, sql, args, e);
            throw new BizException("SQL 执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 只取列元数据（不真正拉取数据），用于数据集"解析字段"。
     */
    public List<ColumnMetaDTO> queryColumns(String dsCode, String sql, List<Object> args) {
        DataSource ds = registry.getOrRegister(dsCode);
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            applyLimits(ps, 1);
            bindArgs(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<ColumnMetaDTO> cols = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    cols.add(new ColumnMetaDTO(label(md, i), mapType(md.getColumnType(i)), md.getColumnLabel(i)));
                }
                return cols;
            }
        } catch (SQLException e) {
            log.error("解析字段失败 dsCode={} sql={} args={}", dsCode, sql, args, e);
            throw new BizException("解析字段失败: " + e.getMessage(), e);
        }
    }

    /** 列出库中的表与视图。 */
    public List<String> listTables(String dsCode) {
        DataSource ds = registry.getOrRegister(dsCode);
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = md.getTables(conn.getCatalog(), conn.getSchema(), "%",
                    new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            tables.sort(String.CASE_INSENSITIVE_ORDER);
            return tables;
        } catch (SQLException e) {
            log.error("获取表清单失败 dsCode={}", dsCode, e);
            throw new BizException("获取表清单失败: " + e.getMessage(), e);
        }
    }

    /** 列出某张表的字段。 */
    public List<ColumnMetaDTO> listColumns(String dsCode, String table) {
        DataSource ds = registry.getOrRegister(dsCode);
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            List<ColumnMetaDTO> cols = new ArrayList<>();
            try (ResultSet rs = md.getColumns(conn.getCatalog(), conn.getSchema(), table, "%")) {
                while (rs.next()) {
                    cols.add(new ColumnMetaDTO(
                            rs.getString("COLUMN_NAME"),
                            mapType(rs.getInt("DATA_TYPE")),
                            rs.getString("REMARKS")));
                }
            }
            return cols;
        } catch (SQLException e) {
            log.error("获取字段清单失败 dsCode={} table={}", dsCode, table, e);
            throw new BizException("获取字段清单失败: " + e.getMessage(), e);
        }
    }

    /* ----------------------------- 内部工具 ----------------------------- */

    private void applyLimits(PreparedStatement ps, int maxRows) throws SQLException {
        ps.setQueryTimeout(props.getQueryTimeout());
        ps.setMaxRows(maxRows);
        ps.setFetchSize(Math.min(500, Math.max(1, maxRows)));
    }

    /**
     * 绑定参数。java.time 类型部分老驱动不支持，统一转成 java.sql 类型再 set。
     */
    private void bindArgs(PreparedStatement ps, List<Object> args) throws SQLException {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.size(); i++) {
            Object v = args.get(i);
            if (v instanceof LocalDate ld) {
                ps.setDate(i + 1, java.sql.Date.valueOf(ld));
            } else if (v instanceof LocalDateTime ldt) {
                ps.setTimestamp(i + 1, java.sql.Timestamp.valueOf(ldt));
            } else if (v instanceof LocalTime lt) {
                ps.setTime(i + 1, java.sql.Time.valueOf(lt));
            } else {
                ps.setObject(i + 1, v);
            }
        }
    }

    private String[] readLabels(ResultSetMetaData md) throws SQLException {
        int n = md.getColumnCount();
        String[] labels = new String[n];
        for (int i = 1; i <= n; i++) {
            labels[i - 1] = label(md, i);
        }
        return labels;
    }

    /** 列标签统一小写；标签为空时退回列名。 */
    private String label(ResultSetMetaData md, int i) throws SQLException {
        String l = md.getColumnLabel(i);
        if (l == null || l.isBlank()) {
            l = md.getColumnName(i);
        }
        return l == null ? ("col" + i) : l.toLowerCase();
    }

    /** JDBC 值 -> 引擎友好的 Java 值。 */
    private Object convert(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime();
        }
        if (v instanceof Clob clob) {
            return clobToString(clob);
        }
        if (v instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return v;
    }

    private String clobToString(Clob clob) {
        try (Reader reader = clob.getCharacterStream()) {
            StringWriter sw = new StringWriter();
            char[] buf = new char[4096];
            int len;
            while ((len = reader.read(buf)) != -1) {
                sw.write(buf, 0, len);
            }
            return sw.toString();
        } catch (Exception e) {
            log.warn("CLOB 读取失败", e);
            return null;
        }
    }

    /** java.sql.Types -> string / number / date / boolean */
    private String mapType(int sqlType) {
        return switch (sqlType) {
            case Types.NUMERIC, Types.DECIMAL, Types.INTEGER, Types.BIGINT, Types.SMALLINT,
                 Types.TINYINT, Types.FLOAT, Types.DOUBLE, Types.REAL -> "number";
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE,
                 Types.TIMESTAMP_WITH_TIMEZONE -> "date";
            case Types.BOOLEAN, Types.BIT -> "boolean";
            default -> "string";
        };
    }
}
