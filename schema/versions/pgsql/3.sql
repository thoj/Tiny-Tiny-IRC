begin;

alter table ttirc_users add column twitter_oauth text;
alter table ttirc_users alter column twitter_oauth set default null;

alter table ttirc_users add column twitter_last_id text;
alter table ttirc_users alter column twitter_last_id set default null;

create table ttirc_snippets(id serial not null primary key,
	key varchar(200) not null,
	snippet text not null,
	created timestamp not null,
	owner_uid integer not null references ttirc_users(id) on delete cascade);

update ttirc_version set schema_version = 3;

commit;
