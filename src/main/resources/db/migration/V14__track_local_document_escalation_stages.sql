alter table indexing_jobs drop constraint indexing_jobs_stage_check;

alter table indexing_jobs
  add constraint indexing_jobs_stage_check check (
    (status = 'QUEUED' and stage = 'QUEUED')
    or (status = 'PROCESSING' and stage in (
      'READING', 'TRANSCRIBING', 'OCR', 'VISUAL_ANALYSIS', 'ORIGINAL_REVIEW',
      'SCREENING', 'CHUNKING', 'EMBEDDING', 'SAVING', 'REINDEXING'
    ))
    or (status = 'AWAITING_APPROVAL' and stage = 'REVIEW_PENDING')
    or (status = 'COMPLETED' and stage = 'COMPLETED')
    or (status = 'FAILED' and stage in (
      'FAILED', 'READING', 'TRANSCRIBING', 'OCR', 'VISUAL_ANALYSIS', 'ORIGINAL_REVIEW',
      'SCREENING', 'CHUNKING', 'EMBEDDING', 'SAVING', 'REINDEXING'
    ))
    or (status = 'REJECTED' and stage = 'REJECTED')
  );
