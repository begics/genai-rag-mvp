drop index if exists chunks_embedding_hnsw_idx;

alter table chunks
  alter column embedding type halfvec(${targetEmbeddingDimensions})
  using case
    when embedding is not null
      and vector_dims(embedding::vector) = ${targetEmbeddingDimensions}
      then embedding::halfvec(${targetEmbeddingDimensions})
    else null::halfvec(${targetEmbeddingDimensions})
  end;

create index chunks_embedding_hnsw_idx
  on chunks using hnsw (embedding halfvec_cosine_ops);
