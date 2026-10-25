-- One row: W, the newest closed live generation (0 before the first close). Live rows with
-- generation <= W are absorbed by the batch.
select cast(coalesce(max(generation), 0) as bigint) as batch_generation

from {{ source('ops', 'live_generation_close') }}
