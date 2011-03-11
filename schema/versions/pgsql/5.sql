begin;

alter table ttirc_connections add column use_ssl bool;
update ttirc_connections set use_ssl = false;
alter table ttirc_connections alter column use_ssl set default false;
alter table ttirc_connections alter column use_ssl set not null;

update ttirc_version set schema_version = 5;

commit;
