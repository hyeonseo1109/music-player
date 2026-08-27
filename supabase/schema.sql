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
create index if not exists lyrics_track_id_idx on lyrics(track_id);
create index if not exists lyrics_author_id_idx on lyrics(author_id);
create index if not exists lyric_lines_lyrics_id_idx on lyric_lines(lyrics_id);
create index if not exists lyrics_reports_lyrics_id_idx on lyrics_reports(lyrics_id);
create index if not exists lyrics_reports_reporter_id_idx on lyrics_reports(reporter_id);
create index if not exists lyrics_votes_user_id_idx on lyrics_votes(user_id);
alter table tracks enable row level security;
alter table lyrics enable row level security;
alter table lyric_lines enable row level security;
alter table lyrics_votes enable row level security;
alter table lyrics_reports enable row level security;
create policy "read tracks" on tracks for select to anon, authenticated using (true);
create policy "authenticated insert tracks" on tracks for insert to authenticated with check(true);
create policy "read lyrics" on lyrics for select to anon, authenticated using (true);
create policy "read lines" on lyric_lines for select to anon, authenticated using (true);
create policy "authenticated insert own lyrics" on lyrics for insert to authenticated with check(author_id = auth.uid());
create policy "author updates own lyrics" on lyrics for update to authenticated using(author_id = auth.uid()) with check(author_id = auth.uid());
create policy "authenticated insert lines for own lyrics" on lyric_lines for insert to authenticated with check(exists(select 1 from lyrics where lyrics.id=lyrics_id and lyrics.author_id=auth.uid()));
create policy "one user one vote" on lyrics_votes for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy "authenticated report" on lyrics_reports for insert to authenticated with check(reporter_id=auth.uid());

-- The (lyrics_id,user_id) primary key is the authoritative duplicate-vote guard.
-- Keep the denormalized count consistent without trusting a client supplied value.
create or replace function refresh_lyrics_vote_count() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  update lyrics set vote_count = (select count(*) from lyrics_votes where lyrics_id = coalesce(new.lyrics_id, old.lyrics_id) and value = 1)
  where id = coalesce(new.lyrics_id, old.lyrics_id);
  return coalesce(new, old);
end $$;
drop trigger if exists lyrics_vote_count_changed on lyrics_votes;
create trigger lyrics_vote_count_changed after insert or update or delete on lyrics_votes
for each row execute function refresh_lyrics_vote_count();

-- New Supabase projects may not expose public tables to the Data API automatically.
-- RLS still decides which rows are visible after these least-privilege grants.
grant usage on schema public to anon, authenticated;
grant select on tracks, lyrics, lyric_lines to anon, authenticated;
grant insert on tracks, lyrics, lyric_lines, lyrics_votes, lyrics_reports to authenticated;
revoke update, delete on tracks, lyrics_votes, lyrics_reports from anon, authenticated;
revoke execute on function refresh_lyrics_vote_count() from public, anon, authenticated;
