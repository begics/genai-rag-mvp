alter table chunks
  add column search_vector tsvector
  generated always as (to_tsvector('simple', content)) stored;

create index chunks_search_vector_idx on chunks using gin (search_vector);
