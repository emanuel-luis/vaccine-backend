select
    cast(receipt_id as uuid) as receipt_id,
    cast(cnes_code as text) as cnes_code,
    cast(lot_number as text) as lot_number,
    cast(received_on as date) as received_on,
    cast(doses_received as integer) as doses_received

from {{ source('raw', 'vaccine_receipt') }}
