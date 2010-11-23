begin;

alter table ttirc_users add column twitter_oauth longtext;
alter table ttirc_users alter column twitter_oauth set default null;

update ttirc_version set schema_version = 3;

commit;
