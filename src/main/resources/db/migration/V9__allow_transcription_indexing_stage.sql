alter table indexing_jobs
  drop constraint indexing_jobs_stage_check;

alter table indexing_jobs
  add constraint indexing_jobs_stage_check check (
    (status = 'QUEUED' and stage = 'QUEUED')
    or (status = 'PROCESSING' and stage in (
      'READING', 'TRANSCRIBING', 'CHUNKING', 'EMBEDDING', 'SAVING', 'REINDEXING'
    ))
    or (status = 'COMPLETED' and stage = 'COMPLETED')
    or (status = 'FAILED' and stage = 'FAILED')
  );
