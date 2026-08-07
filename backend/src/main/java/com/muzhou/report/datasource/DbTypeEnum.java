package com.muzhou.report.datasource;

import lombok.Getter;

/**
 * 支持的数据库类型，提供默认驱动类名与连通性校验 SQL。
 * 取表/列清单统一走 JDBC {@link java.sql.DatabaseMetaData}，此处不提供手写 SQL。
 */
@Getter
public enum DbTypeEnum {

    MYSQL("mysql", "com.mysql.cj.jdbc.Driver", "SELECT 1"),
    POSTGRESQL("postgresql", "org.postgresql.Driver", "SELECT 1"),
    ORACLE("oracle", "oracle.jdbc.OracleDriver", "SELECT 1 FROM DUAL"),
    SQLSERVER("sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver", "SELECT 1"),
    H2("h2", "org.h2.Driver", "SELECT 1");

    /** 与 mz_datasource.db_type 对应的编码，全小写。 */
    private final String code;

    /** 默认驱动类名，当用户未填写 driverClassName 时使用。 */
    private final String driverClassName;

    /** 用于测试连通性的最简 SQL。 */
    private final String validationSql;

    DbTypeEnum(String code, String driverClassName, String validationSql) {
        this.code = code;
        this.driverClassName = driverClassName;
        this.validationSql = validationSql;
    }

    public static DbTypeEnum of(String code) {
        if (code == null) {
            throw new IllegalArgumentException("dbType 不能为空");
        }
        for (DbTypeEnum v : values()) {
            if (v.code.equalsIgnoreCase(code)) {
                return v;
            }
        }
        throw new IllegalArgumentException("不支持的数据库类型: " + code);
    }
}
