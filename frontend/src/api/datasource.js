import request from './request'

export const pageDatasource = (params) => request.get('/datasource/page', { params })
export const listDatasource = () => request.get('/datasource/list')
export const getDatasource = (id) => request.get(`/datasource/${id}`)
export const createDatasource = (data) => request.post('/datasource', data)
export const updateDatasource = (data) => request.put('/datasource', data)
export const deleteDatasource = (id) => request.delete(`/datasource/${id}`)
export const testDatasource = (data) => request.post('/datasource/test', data)
export const listTables = (id) => request.get(`/datasource/${id}/tables`)
export const listColumns = (id, table) => request.get(`/datasource/${id}/columns`, { params: { table } })

/** 常见数据库的驱动 / URL 模板，供新建数据源时自动填充 */
export const DB_TEMPLATES = {
  mysql: {
    label: 'MySQL',
    driverClassName: 'com.mysql.cj.jdbc.Driver',
    url: 'jdbc:mysql://127.0.0.1:3306/db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
  },
  postgresql: {
    label: 'PostgreSQL',
    driverClassName: 'org.postgresql.Driver',
    url: 'jdbc:postgresql://127.0.0.1:5432/db'
  },
  h2: {
    label: 'H2',
    driverClassName: 'org.h2.Driver',
    url: 'jdbc:h2:file:./data/muzhoureport;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE'
  },
  oracle: {
    label: 'Oracle',
    driverClassName: 'oracle.jdbc.OracleDriver',
    url: 'jdbc:oracle:thin:@127.0.0.1:1521:orcl'
  },
  sqlserver: {
    label: 'SQL Server',
    driverClassName: 'com.microsoft.sqlserver.jdbc.SQLServerDriver',
    url: 'jdbc:sqlserver://127.0.0.1:1433;DatabaseName=db'
  }
}
