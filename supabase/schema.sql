-- Run this in a private Supabase project. RLS prevents clients from overwriting other users' lyrics.
create extension if not exists pgcrypto;
create table if not exists tracks (
  id uuid primary key default gen_random_uuid(), track_fingerprint text unique not null,
  normalized_title text not null, normalized_artist text not null, album text,
  duration_ms bigint not null check (duration_ms >= 0), created_at timestamptz default now()
);
create table if not exists lyrics (
  id uuid primary key default gen_random_uuid(), track_id uuid not null references tracks on delete cascade,
  track_fingerprint text not null, author_id uuid references auth.users,
  plain_text text not null check (char_length(plain_text) between 1 and 100000),
  source text default 'community', is_synced boolean default false, vote_count integer default 0,
  created_at timestamptz default now(), updated_at timestamptz default now()
);
create table if not exists lyric_lines (
  id uuid primary key default gen_random_uuid(), lyrics_id uuid not null references lyrics on delete cascade,
  line_index integer not null, start_time_ms bigint not null check(start_time_ms >= 0), text text not null,
  unique(lyrics_id, line_index)
);
create table if not exists lyrics_votes (
  lyrics_id uuid references lyrics on delete cascade, user_id uuid references auth.users on delete cascade,
  value smallint not null check(value in (-1,1)), primary key(lyrics_id,user_id)
);
create table if not exists lyrics_reports (
  id uuid primary key default gen_random_uuid(), lyrics_id uuid references lyrics on delete cascade,
  reporter_id uuid references auth.users, reason text not null, created_at timestamptz default now()
);
alter table tracks enable row level security;
alter table lyrics enable row level security;
alter table lyric_lines enable row level security;
alter table lyrics_votes enable row level security;
alter table lyrics_reports enable row level security;
create policy "read tracks" on tracks for select to anon, authenticated using (true);
create policy "read lyrics" on lyrics for select to anon, authenticated using (true);
create policy "read lines" on lyric_lines for select to anon, authenticated using (true);
create policy "authenticated insert own lyrics" on lyrics for insert to authenticated with check(author_id = auth.uid());
create policy "author updates own lyrics" on lyrics for update to authenticated using(author_id = auth.uid()) with check(author_id = auth.uid());
create policy "authenticated insert lines for own lyrics" on lyric_lines for insert to authenticated with check(exists(select 1 from lyrics where lyrics.id=lyrics_id and lyrics.author_id=auth.uid()));
create policy "one user one vote" on lyrics_votes for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy "authenticated report" on lyrics_reports for insert to authenticated with check(reporter_id=auth.uid());
