{{
  config(
    materialized='view'
  )
}}

select
    s.suppkey,
    s.name as supplier_name,
    s.address,
    s.nationkey,
    n.regionkey,
    s.phone,
    s.acctbal as account_balance,
    s.comment
from {{ source('ice_db', 'supplier') }} s
join {{ source('ice_db', 'nation') }} n on s.nationkey = n.nationkey
