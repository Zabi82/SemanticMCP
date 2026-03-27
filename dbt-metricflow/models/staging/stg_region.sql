{{
  config(
    materialized='view'
  )
}}

select
    regionkey,
    name as region_name,
    comment
from {{ source('ice_db', 'region') }}
