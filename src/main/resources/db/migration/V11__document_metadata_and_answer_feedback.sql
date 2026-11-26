alter table documents add column document_type varchar(20);

update documents
   set document_type = case
     when lower(source) like '%.pdf' then 'pdf'
     when lower(source) like '%.csv' then 'csv'
     when lower(source) like '%.md' then 'markdown'
     when lower(source) like '%.txt' then 'text'
     when lower(source) like '%.mp3' or lower(source) like '%.mp4'
       or lower(source) like '%.ogg' or lower(source) like '%.opus'
       or lower(source) like '%.wav' or lower(source) like '%.webm'
       or lower(source) like '%.wma' or lower(source) like '%.m4a'
       or lower(source) like '%.aac' or lower(source) like '%.flac' then 'media'
     else 'other'
   end;

alter table documents alter column document_type set not null;
create index documents_workspace_type_idx on documents (workspace_id, document_type);

create table answer_feedback (
  id bigserial primary key,
  workspace_id uuid not null references workspaces(id) on delete cascade,
  subject varchar(200) not null,
  question varchar(10000) not null,
  answer text not null,
  helpful boolean not null,
  comment varchar(2000),
  source_ids jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now()
);

create index answer_feedback_workspace_created_idx
  on answer_feedback (workspace_id, created_at desc);
