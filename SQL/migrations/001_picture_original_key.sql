-- 仅对已有数据库执行一次；新数据库使用 create_table.sql。
ALTER TABLE picture ADD COLUMN originalKey varchar(512) NULL COMMENT 'COS 原图对象 Key';
