alter table indexing_jobs
  add column stage varchar(20) not null default 'QUEUED';

update indexing_jobs
   set stage = case status
     when 'COMPLETED' then 'COMPLETED'
     when 'FAILED' then 'FAILED'
     when 'PROCESSING' then 'READING'
     else 'QUEUED'
   end;

alter table indexing_jobs
  add constraint indexing_jobs_stage_check check (
    (status = 'QUEUED' and stage = 'QUEUED')
    or (status = 'PROCESSING' and stage in (
      'READING', 'CHUNKING', 'EMBEDDING', 'SAVING', 'REINDEXING'
    ))
    or (status = 'COMPLETED' and stage = 'COMPLETED')
    or (status = 'FAILED' and stage = 'FAILED')
  );
