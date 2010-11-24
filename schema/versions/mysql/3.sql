begin;

alter table ttirc_users add column twitter_oauth longtext;
alter table ttirc_users alter column twitter_oauth set default null;

create table ttirc_snippets(id integer not null primary key auto_increment,
	key varchar(200) not null,
	snippet longtext not null,
	created datetime not null,
	owner_uid integer not null references ttirc_users(id) on delete cascade) TYPE=InnoDB DEFAULT CHARSET=UTF8;

update ttirc_version set schema_version = 3;

commit;
