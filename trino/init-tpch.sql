-- TPCH Data Ingestion Script
-- This script ingests TPCH benchmark data from tpch.tiny catalog into Iceberg tables
-- Executed automatically during Trino container startup

-- Create schema for Iceberg tables
CREATE SCHEMA IF NOT EXISTS semantic_demo.ice_db;

-- Ingest TPCH tables as Iceberg tables with Parquet format
CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.customer 
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.customer;

CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.orders
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.orders;

CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.lineitem
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.lineitem;

CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.part
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.part;

CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.supplier
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.supplier;

CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.partsupp
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.partsupp;

CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.nation
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.nation;

CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.region
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.region;
