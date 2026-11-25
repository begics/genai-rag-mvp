drop index if exists chunks_embedding_idx;

alter table chunks drop column embedding_legacy;
