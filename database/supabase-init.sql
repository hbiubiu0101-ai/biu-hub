-- BIU HUB V1.5 / Supabase initialization
-- Run this file ONCE in Supabase SQL Editor.
-- This table stores the current personal bookkeeping state used by the existing UI.

create table if not exists public.bookkeeping_state (
  id bigint primary key,
  data_json jsonb not null default '{}'::jsonb,
  budget_json jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

alter table public.bookkeeping_state enable row level security;

drop policy if exists "biu_bookkeeping_read" on public.bookkeeping_state;
drop policy if exists "biu_bookkeeping_insert" on public.bookkeeping_state;
drop policy if exists "biu_bookkeeping_update" on public.bookkeeping_state;

create policy "biu_bookkeeping_read"
on public.bookkeeping_state
for select
to anon
using (id = 1);

create policy "biu_bookkeeping_insert"
on public.bookkeeping_state
for insert
to anon
with check (id = 1);

create policy "biu_bookkeeping_update"
on public.bookkeeping_state
for update
to anon
using (id = 1)
with check (id = 1);

insert into public.bookkeeping_state (id, data_json, budget_json)
values (1, '{}'::jsonb, '{}'::jsonb)
on conflict (id) do nothing;
