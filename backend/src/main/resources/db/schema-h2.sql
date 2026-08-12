-- public.mz_dataset 定义

-- Drop table

-- DROP TABLE public.mz_dataset;

CREATE TABLE public.mz_dataset (
                                   id character varying(32) NOT NULL,
                                   name character varying(100) NOT NULL,
                                   code character varying(50) NOT NULL,
                                   report_id character varying(32) DEFAULT '',
                                   datasource_id character varying(32),
                                   "type" character varying(20) DEFAULT 'sql',
                                   result_type character varying(20) DEFAULT 'list',
                                   sql_text character large object,
                                   api_url character varying(1000),
                                   api_method character varying(10),
                                   api_headers character varying(2000),
                                   json_text character large object,
                                   remark character varying(500),
                                   status integer DEFAULT 1,
                                   deleted integer DEFAULT 0,
                                   create_time timestamp,
                                   update_time timestamp
);
CREATE UNIQUE INDEX "PRIMARY_KEY_2" ON public.mz_dataset (id);
CREATE UNIQUE INDEX uk_dataset_code_scope ON public.mz_dataset (code,report_id);
-- 设计器左侧的数据集树按 report_id 查（DatasetServiceImpl#listForReport），
-- 而上面那个唯一索引的前导列是 code，用不上它。新增索引一律 IF NOT EXISTS —— 老库已经建过了
CREATE INDEX IF NOT EXISTS idx_dataset_report ON public.mz_dataset (report_id);
-- 作用范围的第三级：version_id 非空 = 只属于那一版（不同版本可以有同 code 不同接口的数据集）。
-- 空串 = 老语义（公共 / 该报表全版本共用），所以老数据一行都不用洗
ALTER TABLE public.mz_dataset ADD COLUMN IF NOT EXISTS version_id character varying(32) DEFAULT '';
-- 唯一索引要跟着扩一列，否则 v1 与 v2 各有一个 code=orders 的数据集会撞唯一键。
-- 老索引换成新的：DROP 只在第一次真的做事，往后是空跑
DROP INDEX IF EXISTS uk_dataset_code_scope;
CREATE UNIQUE INDEX IF NOT EXISTS uk_dataset_code_scope_v ON public.mz_dataset (code,report_id,version_id);


-- public.mz_dataset_field 定义

-- Drop table

-- DROP TABLE public.mz_dataset_field;

CREATE TABLE public.mz_dataset_field (
                                         id character varying(32) NOT NULL,
                                         dataset_id character varying(32) NOT NULL,
                                         field_name character varying(100),
                                         field_text character varying(100),
                                         field_type character varying(20) DEFAULT 'string',
                                         sort_no integer DEFAULT 0
);
CREATE UNIQUE INDEX "PRIMARY_KEY_CD" ON public.mz_dataset_field (id);
CREATE INDEX idx_field_dataset ON public.mz_dataset_field (dataset_id);


-- public.mz_dataset_param 定义

-- Drop table

-- DROP TABLE public.mz_dataset_param;

CREATE TABLE public.mz_dataset_param (
                                         id character varying(32) NOT NULL,
                                         dataset_id character varying(32) NOT NULL,
                                         param_name character varying(100),
                                         param_text character varying(100),
                                         param_type character varying(20) DEFAULT 'string',
                                         default_value character varying(500),
                                         required integer DEFAULT 0,
                                         sort_no integer DEFAULT 0
);
CREATE UNIQUE INDEX "PRIMARY_KEY_CE" ON public.mz_dataset_param (id);
CREATE INDEX idx_param_dataset ON public.mz_dataset_param (dataset_id);


-- public.mz_datasource 定义

-- Drop table

-- DROP TABLE public.mz_datasource;

CREATE TABLE public.mz_datasource (
                                      id character varying(32) NOT NULL,
                                      name character varying(100) NOT NULL,
                                      code character varying(50) NOT NULL,
                                      db_type character varying(20) NOT NULL,
                                      driver_class_name character varying(200),
                                      url character varying(1000),
                                      username character varying(100),
                                      password character varying(500),
                                      remark character varying(500),
                                      status integer DEFAULT 1,
                                      deleted integer DEFAULT 0,
                                      create_time timestamp,
                                      update_time timestamp
);
CREATE UNIQUE INDEX "PRIMARY_KEY_C" ON public.mz_datasource (id);
CREATE UNIQUE INDEX uk_ds_code ON public.mz_datasource (code);


-- public.mz_report 定义

-- Drop table

-- DROP TABLE public.mz_report;

CREATE TABLE public.mz_report (
                                  id character varying(32) NOT NULL,
                                  name character varying(100) NOT NULL,
                                  code character varying(50) NOT NULL,
                                  "type" character varying(20) DEFAULT 'sheet',
                                  content character large object,
                                  remark character varying(500),
                                  status integer DEFAULT 1,
                                  deleted integer DEFAULT 0,
                                  create_by character varying(50),
                                  create_time timestamp,
                                  update_time timestamp
);
CREATE UNIQUE INDEX "PRIMARY_KEY_8" ON public.mz_report (id);
CREATE UNIQUE INDEX uk_report_code ON public.mz_report (code);

-- 版本切换规则（报表级），见 docs/CONTRACT.md §2 / §4：
-- {"source":"field","field":"order_date","fallback":"default"}
-- 规则不能放进 content —— content 本身就是被版本化的那个东西，放进去就成了
-- 「每个版本各有一套怎么选自己」，逻辑成环。
ALTER TABLE public.mz_report ADD COLUMN IF NOT EXISTS version_config character varying(500);


-- public.mz_report_version 定义
--
-- 一张报表的一份**版式**（content）。版本化的是版式，不是数据集 —— 数据集是「取数」，
-- 跨版本共用（见 CONTRACT §2）。生效区间**两端都存**（左闭右开），两端都可为空 = 不限；
-- 允许多版重叠，重叠时起点更晚的赢（见 CONTRACT §4.1）。

CREATE TABLE IF NOT EXISTS public.mz_report_version (
                                  id character varying(32) NOT NULL,
                                  report_id character varying(32) NOT NULL,
                                  version_no integer NOT NULL,
                                  name character varying(50),
                                  content character large object,
                                  effective_from timestamp,
                                  effective_to timestamp,
                                  match_rules character varying(2000),
                                  is_default integer DEFAULT 0,
                                  status integer DEFAULT 1,
                                  remark character varying(500),
                                  deleted integer DEFAULT 0,
                                  create_by character varying(50),
                                  create_time timestamp,
                                  update_time timestamp
);
CREATE UNIQUE INDEX IF NOT EXISTS "PRIMARY_KEY_V" ON public.mz_report_version (id);
-- 逻辑删除不会把行删掉，所以这个唯一索引连已删版本一起管着 ——
-- version_no 必须按「含已删行的 max+1」发号，否则删掉 v3 再建一个就撞车
CREATE UNIQUE INDEX IF NOT EXISTS uk_version_report_no ON public.mz_report_version (report_id,version_no);
CREATE INDEX IF NOT EXISTS idx_version_report ON public.mz_report_version (report_id);
-- 版本的匹配条件（离散那一维：类型/区域…），见 CONTRACT §4.1：
-- [{"source":"field","field":"order_type","op":"eq","value":"A"}]
-- 老库里这一列不存在，所以照 version_config 那条的样子补一句 ADD COLUMN IF NOT EXISTS
ALTER TABLE public.mz_report_version ADD COLUMN IF NOT EXISTS match_rules character varying(2000);
-- 生效**结束**时刻（左闭右开的右端），NULL = 不限。早先只存起点、右端靠排序推，
-- 推出来的右端说不了「这一版 8/1 就到期，之后谁也不接」，也表达不了两版共用同一段时间。
-- 老库里这一列同样不存在：补上之后全是 NULL = 右端不限，推导出来的老区间原样成立
-- （重叠时起点更晚的赢，正好等于原先「取 effective_from ≤ 判定值的最后一个」）
ALTER TABLE public.mz_report_version ADD COLUMN IF NOT EXISTS effective_to timestamp;


-- public.mz_param 定义
--
-- 全局参数：一份系统级的参数定义，所有报表共用（见 CONTRACT §2 mz_param / §3.5）。
-- 列名跟 mz_dataset_param 一套（param_name/param_text/param_type）而不是 name/text/type，
-- text 在几种数据库里是类型名，当列名要处处加引号。

CREATE TABLE IF NOT EXISTS public.mz_param (
                                  id character varying(32) NOT NULL,
                                  param_name character varying(64) NOT NULL,
                                  param_text character varying(128),
                                  param_type character varying(20) DEFAULT 'string',
                                  widget character varying(20) DEFAULT 'input',
                                  default_value character varying(500),
                                  required integer DEFAULT 0,
                                  options character varying(2000),
                                  remark character varying(500),
                                  status integer DEFAULT 1,
                                  deleted integer DEFAULT 0,
                                  create_time timestamp,
                                  update_time timestamp
);
CREATE UNIQUE INDEX IF NOT EXISTS "PRIMARY_KEY_MP" ON public.mz_param (id);
-- 逻辑删除不会把行删掉，所以这个唯一索引连已删参数一起管着 ——
-- 删掉再建同名参数会撞唯一键，新建/改名前先物理清掉同名的已删除行（MzParamMapper#purgeDeletedByName）
CREATE UNIQUE INDEX IF NOT EXISTS uk_param_name ON public.mz_param (param_name);


-- public.order_items 定义

-- Drop table

-- DROP TABLE public.order_items;

CREATE TABLE public.order_items (
                                    item_id integer NOT NULL,
                                    order_id character varying(20) NOT NULL,
                                    product_id character varying(20) NOT NULL,
                                    product_name character varying(100) NOT NULL,
                                    quantity integer NOT NULL,
                                    unit_price decimal(10,2) NOT NULL,
                                    subtotal decimal(12,2) NOT NULL
);
CREATE UNIQUE INDEX "PRIMARY_KEY_7" ON public.order_items (item_id);


-- public.order_shipments 定义

-- Drop table

-- DROP TABLE public.order_shipments;

CREATE TABLE public.order_shipments (
                                        shipment_id character varying(20) NOT NULL,
                                        order_id character varying(20) NOT NULL,
                                        carrier character varying(50) NOT NULL,
                                        tracking_number character varying(30),
                                        ship_date date,
                                        delivery_date date,
                                        status character varying(20) NOT NULL,
                                        shipping_address character varying(200) NOT NULL,
                                        receiver_name character varying(50) NOT NULL,
                                        receiver_phone character varying(20) NOT NULL
);
CREATE UNIQUE INDEX "PRIMARY_KEY_F" ON public.order_shipments (shipment_id);


-- public.orders 定义

-- Drop table

-- DROP TABLE public.orders;

CREATE TABLE public.orders (
                               order_id character varying(20) NOT NULL,
                               customer_id character varying(20) NOT NULL,
                               order_date date NOT NULL,
                               order_status character varying(20) NOT NULL,
                               total_amount decimal(12,2) NOT NULL,
                               payment_method character varying(20),
                               created_at timestamp NOT NULL
);
CREATE UNIQUE INDEX "PRIMARY_KEY_C3" ON public.orders (order_id);