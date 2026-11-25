alter table indexing_jobs
  add column job_type varchar(20) not null default 'INDEX'
  check (job_type in ('INDEX', 'REEMBED'));

alter table indexing_jobs drop constraint indexing_jobs_check;

alter table indexing_jobs
  add constraint indexing_jobs_payload_check check (
    (
      status in ('QUEUED', 'PROCESSING')
      and (
        (job_type = 'INDEX' and ((object_key is null) <> (content is null)))
        or (job_type = 'REEMBED' and object_key is null and content is null)
      )
    )
    or (status in ('COMPLETED', 'FAILED') and object_key is null and content is null)
  );
