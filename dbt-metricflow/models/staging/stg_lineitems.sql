{{
  config(
    materialized='view'
  )
}}

select
    orderkey,
    partkey,
    suppkey,
    linenumber,
    quantity,
    extendedprice,
    discount,
    tax,
    returnflag,
    linestatus,
    shipdate,
    commitdate,
    receiptdate,
    shipinstruct,
    shipmode,
    comment
from {{ source('ice_db', 'lineitem') }}
