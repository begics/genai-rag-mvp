create table workspaces (
  id uuid primary key,
  name varchar(120) not null,
  created_at timestamptz not null default now()
);

create table workspace_members (
  workspace_id uuid not null references workspaces(id) on delete cascade,
  subject varchar(200) not null,
  role varchar(20) not null check (role in ('OWNER', 'MEMBER')),
  created_at timestamptz not null default now(),
  primary key (workspace_id, subject)
);

insert into workspaces(id, name)
values ('00000000-0000-0000-0000-000000000001', 'Legacy documents');

alter table documents add column workspace_id uuid;
update documents set workspace_id = '00000000-0000-0000-0000-000000000001';
alter table documents alter column workspace_id set not null;
alter table documents add constraint documents_workspace_fk
  foreign key (workspace_id) references workspaces(id);
create index documents_workspace_idx on documents (workspace_id, created_at desc);

alter table indexing_jobs add column workspace_id uuid;
update indexing_jobs set workspace_id = '00000000-0000-0000-0000-000000000001';
alter table indexing_jobs alter column workspace_id set not null;
alter table indexing_jobs add constraint indexing_jobs_workspace_fk
  foreign key (workspace_id) references workspaces(id);
create index indexing_jobs_workspace_status_idx on indexing_jobs (workspace_id, status, created_at);
