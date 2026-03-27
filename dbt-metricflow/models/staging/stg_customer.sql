{{
  config(
    materialized='view'
  )
}}

select
    custkey,
    name as customer_name,
    address,
    nationkey,
    phone,
    acctbal as account_balance,
    mktsegment as market_segment,
    comment
from {{ source('ice_db', 'customer') }}
