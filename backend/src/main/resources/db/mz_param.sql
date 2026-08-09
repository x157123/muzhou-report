-- 全局参数演示数据（见 CONTRACT §2 mz_param）。
-- 纯 INSERT 不是 MERGE：库已建好之后再跑会撞主键、被 continue-on-error 吞掉，
-- 也就是只对空库生效，不会把你在界面上改过的演示数据覆盖回去。
--
-- 挑的这两个都是「每张报表都要、却和取数无关」的那类：抬头和打印人。
-- 单元格里写 ${companyName} 就能取到，不必在每张报表里各声明一遍 —— 这正是全局参数的用处。
INSERT INTO public.mz_param (id,param_name,param_text,param_type,widget,default_value,required,options,remark,status,deleted,create_time,update_time) VALUES
	 ('a1e5c6d74b8f4c0a9d3e2f10b7c85d31','companyName','公司抬头','string','input','木舟软件有限公司',0,NULL,'页头/表头上的单位名称，写 ${companyName} 引用',1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
	 ('b2f6d7e85c9a4d1b8e4f3021c8d96e42','printUser','打印人','string','input','',0,NULL,'谁打的这份表，写 ${printUser} 引用',1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
