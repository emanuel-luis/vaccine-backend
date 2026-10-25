{#
    Live generation protocol (docs/data-model.md). The on-run-start hook closes the open generation
    before any model reads raw.live_dose, but only for commands that build models: run and build.
    Other commands (test, parse, compile, docs generate) get a no-op, so they never close a
    generation that no model absorbs. dbt-postgres commits each run hook as it finishes (checked: ingest
    was never blocked while a build ran), so the SHARE lock on raw.live_dose is not kept for the run.
#}
{% macro close_live_generation() -%}
    {%- if flags.WHICH in ('run', 'build') -%}
        select ops.close_live_generation('{{ invocation_id }}')
    {%- else -%}
        select 1
    {%- endif -%}
{%- endmacro %}

{# on-run-end: tell the API's LISTEN hub which generation this build absorbed. #}
{% macro notify_batch_event(results) -%}
    {%- if flags.WHICH in ('run', 'build') and (results | selectattr('status', 'equalto', 'error') | list | length) == 0 -%}
        select pg_notify(
            'batch_event',
            json_build_object(
                'generation',
                (select generation from ops.live_generation_close where closed_by = '{{ invocation_id }}'),
                'invocationId', '{{ invocation_id }}'
            )::text
        )
    {%- else -%}
        select 1
    {%- endif -%}
{%- endmacro %}
