alter table chunks rename column embedding to embedding_legacy;
alter table chunks alter column embedding_legacy drop not null;

alter table chunks
  add column embedding halfvec(3584);

create index chunks_embedding_hnsw_idx
  on chunks using hnsw (embedding halfvec_cosine_ops);
