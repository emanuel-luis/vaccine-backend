{#
    Surrogate keys are uuids built from the natural key (md5 hex cast to uuid), so they are stable
    across rebuilds. Usage: {{ uuid_key(['municipality_code']) }}
#}
{% macro uuid_key(columns) -%}
    ({{ dbt_utils.generate_surrogate_key(columns) }})::uuid
{%- endmacro %}

{# Key of the Unknown member every dimension except dim_date carries. #}
{% macro unknown_key() -%}
    '00000000-0000-0000-0000-000000000000'::uuid
{%- endmacro %}
