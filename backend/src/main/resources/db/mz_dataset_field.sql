INSERT INTO public.mz_dataset_field (id,dataset_id,field_name,field_text,field_type,sort_no) VALUES
	 ('63cb5989d9fc8fc6c252b539b3cab50f','dad5e802f66fecdbb86f05cf98de5c8a','item_id','item_id','number',1),
	 ('d4a7aaf0e9252f5e5dcd58d212bd7b26','dad5e802f66fecdbb86f05cf98de5c8a','order_id','order_id','string',2),
	 ('01149deee8d4f94f31eaa9a19c02c6ca','dad5e802f66fecdbb86f05cf98de5c8a','product_id','product_id','string',3),
	 ('0720b1834f0c29e7bfb9dd3a11473adb','dad5e802f66fecdbb86f05cf98de5c8a','product_name','product_name','string',4),
	 ('e3c07baa16b644ddc73e81fd78df426f','dad5e802f66fecdbb86f05cf98de5c8a','quantity','quantity','number',5),
	 ('b5d799e71aec1cb7674276ed867e3e31','dad5e802f66fecdbb86f05cf98de5c8a','unit_price','unit_price','number',6),
	 ('1fb1519ecb0570419b1b1e2a8bc925ae','dad5e802f66fecdbb86f05cf98de5c8a','subtotal','subtotal','number',7),
	 ('a61fd82823419c4e7411f38470384c91','6b4fc47fc41f1a37c0448e3c8bad4b88','shipment_id','shipment_id','string',1),
	 ('ebf7689050063d31b1c5769972021044','6b4fc47fc41f1a37c0448e3c8bad4b88','order_id','订单id','string',2),
	 ('a1dd87a586c630eae30bc27eb5185140','6b4fc47fc41f1a37c0448e3c8bad4b88','carrier','物流','string',3);
INSERT INTO public.mz_dataset_field (id,dataset_id,field_name,field_text,field_type,sort_no) VALUES
	 ('896a63d63317c0b8790665c2ee027d71','6b4fc47fc41f1a37c0448e3c8bad4b88','tracking_number','物流id','string',4),
	 ('732c94c995f1ece338a8b8c9bb499e5a','6b4fc47fc41f1a37c0448e3c8bad4b88','ship_date','发货时间','date',5),
	 ('475ecc1b0edd07664acdded9472ae617','6b4fc47fc41f1a37c0448e3c8bad4b88','delivery_date','签收时间','date',6),
	 ('4b94036728e5b085c033a266d9a4f5d6','6b4fc47fc41f1a37c0448e3c8bad4b88','status','状态','string',7),
	 ('e59ee521178673b97b25e23556ca38b4','6b4fc47fc41f1a37c0448e3c8bad4b88','shipping_address','地址','string',8),
	 ('e31e05ce0d0bf711939d8fe3fa1b0697','6b4fc47fc41f1a37c0448e3c8bad4b88','receiver_name','联系人','string',9),
	 ('8e201dc36e1fbe632918bcef0a64c044','6b4fc47fc41f1a37c0448e3c8bad4b88','receiver_phone','电话','string',10),
	 ('5364e4fc6bfb71a602ea3bed2bc9dff2','1a021b69fbd5fc140ecfb3560beb6404','item_id','编号','number',1),
	 ('41b4ec86cf29cdc10bd9b0c39556681d','1a021b69fbd5fc140ecfb3560beb6404','order_id','订单id','string',2),
	 ('1b1d7e990115abd2765a9c8ab1996918','1a021b69fbd5fc140ecfb3560beb6404','product_id','product_id','string',3);
INSERT INTO public.mz_dataset_field (id,dataset_id,field_name,field_text,field_type,sort_no) VALUES
	 ('a7c6934c1b352afaf1c3ab3c48ae5def','1a021b69fbd5fc140ecfb3560beb6404','product_name','名称','string',4),
	 ('22baeea61ba47ae35cf891ce2f895659','1a021b69fbd5fc140ecfb3560beb6404','quantity','数量','number',5),
	 ('82d2d5c7b8a9b38f5ff87080f1358cde','1a021b69fbd5fc140ecfb3560beb6404','unit_price','价格','number',6),
	 ('5acfb46ecc4bad99a985fb66835d14a3','1a021b69fbd5fc140ecfb3560beb6404','subtotal','合计','number',7),
	 ('16c06382ac01f255fc514c674603f10e','b6c16d742e24dd17fba2842b8d2bcc6a','order_id','订单id','string',1),
	 ('3deead21509e5dd7ebe7d8dbf4c68e19','b6c16d742e24dd17fba2842b8d2bcc6a','customer_id','customer_id','string',2),
	 ('3c6326fb6e8dbe9a2b8ae874d0d06410','b6c16d742e24dd17fba2842b8d2bcc6a','order_date','订单时间','date',3),
	 ('7bc67835e2fbae30867f4ffc870dcade','b6c16d742e24dd17fba2842b8d2bcc6a','order_status','订单状态','string',4),
	 ('6e919fe0c3b09936d72e599343a7db5b','b6c16d742e24dd17fba2842b8d2bcc6a','total_amount','价格','number',5),
	 ('7ecf453dc62ea4eeefca038030eb048e','b6c16d742e24dd17fba2842b8d2bcc6a','payment_method','支付方式','string',6);
INSERT INTO public.mz_dataset_field (id,dataset_id,field_name,field_text,field_type,sort_no) VALUES
	 ('f16e485355e7287b4d359063cfa3b19f','b6c16d742e24dd17fba2842b8d2bcc6a','created_at','创建时间','date',7);
