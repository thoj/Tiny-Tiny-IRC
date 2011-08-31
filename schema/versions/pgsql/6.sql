begin;

alter table ttirc_users add column hide_join_part boolean;

update ttirc_users set hide_join_part = false;

alter table ttirc_users alter column hide_join_part set not null;
alter table ttirc_users alter column hide_join_part set default false;

update ttirc_version set schema_version = 6;

commit;
