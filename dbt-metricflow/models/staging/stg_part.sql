{{
  config(
    materialized='view'
  )
}}

select
    partkey,
    name as part_name,
    mfgr as manufacturer,
    brand,
    type as part_type,
    size as part_size,
    container,
    retailprice as retail_price,
    comment
from {{ source('ice_db', 'part') }}
