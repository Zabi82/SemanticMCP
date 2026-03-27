{{
  config(
    materialized='view'
  )
}}

select
    nationkey,
    name as nation_name,
    regionkey,
    comment
from {{ source('ice_db', 'nation') }}
