alter table chunks add column embedding_model varchar(200);
create index chunks_embedding_model_idx on chunks (embedding_model);

create table indexing_jobs (
  id uuid primary key,
  source varchar(500) not null,
  object_key varchar(1000),
  content text,
  status varchar(20) not null check (status in ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED')),
  chunk_count integer not null default 0,
  error_message varchar(1_000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (
    (status in ('QUEUED', 'PROCESSING') and ((object_key is null) <> (content is null)))
    or (status in ('COMPLETED', 'FAILED') and object_key is null and content is null)
  )
);

create index indexing_jobs_status_created_idx on indexing_jobs (status, created_at);
