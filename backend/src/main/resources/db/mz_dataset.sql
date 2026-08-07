INSERT INTO public.mz_dataset (id,name,code,report_id,datasource_id,"type",result_type,sql_text,api_url,api_method,api_headers,json_text,remark,status,deleted,create_time,update_time) VALUES
	 ('b6c16d742e24dd17fba2842b8d2bcc6a','orders','orders','','ds_demo_h2','sql','list','SELECT order_id, customer_id, order_date, order_status, total_amount, payment_method, created_at
FROM public.orders limit 200;','','GET','','','',1,0,'2026-08-03 09:54:23.072472','2026-08-03 10:12:55.08672'),
	 ('1a021b69fbd5fc140ecfb3560beb6404','order_items','order_items','','ds_demo_h2','sql','list','SELECT item_id, order_id, product_id, product_name, quantity, unit_price, subtotal
FROM public.order_items where order_id = ${order_id};','','GET','','','order_items',1,0,'2026-08-03 09:56:11.74432','2026-08-03 10:01:47.517051'),
	 ('6b4fc47fc41f1a37c0448e3c8bad4b88','order_shipments','order_shipments','','ds_demo_h2','sql','list','SELECT shipment_id, order_id, carrier, tracking_number, ship_date, delivery_date, status, shipping_address, receiver_name, receiver_phone
FROM public.order_shipments where order_id = ${order_id};','','GET','','','订单流程',1,0,'2026-08-03 09:58:52.280939','2026-08-03 10:00:37.694672');
