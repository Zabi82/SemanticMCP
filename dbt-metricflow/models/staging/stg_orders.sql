{{
  config(
    materialized='view'
  )
}}

select
    orderkey,
    custkey,
    orderstatus,
    totalprice,
    orderdate,
    orderpriority,
    clerk,
    shippriority,
    comment
from {{ source('ice_db', 'orders') }}
