begin;

alter table ttirc_connections add column server_password varchar(120);
update ttirc_connections set server_password = '';
alter table ttirc_connections alter column server_password set default '';
alter table ttirc_connections alter column server_password set not null;

update ttirc_version set schema_version = 4;

commit;
