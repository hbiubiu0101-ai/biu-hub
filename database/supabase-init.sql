create table if not exists public.bookkeeping_state (
  id bigint primary key,
  data_json jsonb not null default '{}'::jsonb,
  budget_json jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

alter table public.bookkeeping_state enable row level security;

insert into public.bookkeeping_state (id, data_json, budget_json)
values (1, '{}'::jsonb, '{}'::jsonb)
on conflict (id) do nothing;
