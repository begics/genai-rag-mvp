create table uploaded_objects (
  object_key varchar(1000) primary key,
  workspace_id uuid not null references workspaces(id) on delete cascade,
  uploaded_by varchar(200) not null,
  uploaded_at timestamptz not null default now()
);

alter table indexing_jobs
  add column submitted_by varchar(200),
  add column review_findings jsonb not null default '[]'::jsonb,
  add column screened_at timestamptz,
  add column reviewed_by varchar(200),
  add column reviewed_at timestamptz,
  add column indexed_document_id varchar(36),
  add column indexed_at timestamptz,
  add column review_required boolean not null default false;

alter table indexing_jobs drop constraint indexing_jobs_status_check;
alter table indexing_jobs drop constraint indexing_jobs_payload_check;
alter table indexing_jobs drop constraint indexing_jobs_stage_check;

alter table indexing_jobs
  add constraint indexing_jobs_status_check
  check (status in ('QUEUED', 'PROCESSING', 'AWAITING_APPROVAL', 'COMPLETED', 'FAILED', 'REJECTED'));

alter table indexing_jobs
  add constraint indexing_jobs_payload_check check (
    (
      status in ('QUEUED', 'PROCESSING')
      and (
        (job_type = 'INDEX' and (object_key is not null or content is not null)
         and (not review_required or screened_at is null or reviewed_at is not null))
        or (job_type = 'REEMBED' and object_key is null and content is null)
      )
    )
    or (
      status = 'AWAITING_APPROVAL' and job_type = 'INDEX' and review_required
      and object_key is null and content is not null and screened_at is not null and reviewed_at is null
    )
    or (
      status = 'COMPLETED' and object_key is null and content is null
      and (job_type = 'REEMBED' or not review_required or (screened_at is not null and reviewed_at is not null))
    )
    or (status in ('FAILED', 'REJECTED') and object_key is null and content is null)
  );

alter table indexing_jobs
  add constraint indexing_jobs_stage_check check (
    (status = 'QUEUED' and stage = 'QUEUED')
    or (status = 'PROCESSING' and stage in (
      'READING', 'TRANSCRIBING', 'SCREENING', 'CHUNKING', 'EMBEDDING', 'SAVING', 'REINDEXING'
    ))
    or (status = 'AWAITING_APPROVAL' and stage = 'REVIEW_PENDING')
    or (status = 'COMPLETED' and stage = 'COMPLETED')
    or (status = 'FAILED' and stage in (
      'FAILED', 'READING', 'TRANSCRIBING', 'SCREENING', 'CHUNKING', 'EMBEDDING', 'SAVING', 'REINDEXING'
    ))
    or (status = 'REJECTED' and stage = 'REJECTED')
  );

create index uploaded_objects_workspace_idx on uploaded_objects (workspace_id, uploaded_at desc);
create index indexing_jobs_review_idx on indexing_jobs (workspace_id, status, created_at desc);
