select
    cast(lot_number as text) as lot_number,
    cast(vaccine_code as text) as vaccine_code,
    cast(manufacturer_name as text) as manufacturer_name,
    cast(manufactured_on as date) as manufactured_on,
    cast(expires_on as date) as expires_on

from {{ source('raw', 'vaccine_lot') }}
