create extension if not exists vector;
create table documents (
  id uuid primary key,
  source varchar(500) not null,
  created_at timestamptz not null default now()
);
create table chunks (
  id bigserial primary key,
  document_id uuid not null references documents(id) on delete cascade,
  chunk_no integer not null,
  content text not null,
  embedding vector(${embeddingDimensions}) not null,
  unique(document_id, chunk_no)
);
create index chunks_embedding_idx on chunks using hnsw (embedding vector_cosine_ops);
