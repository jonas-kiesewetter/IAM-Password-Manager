create sequence petshop_orderseq start with 1 increment by 4;
create sequence petshop_petseq start with 1 increment by 4;

    create table petshop_pet (
       dc_id integer not null,
        dc_age integer not null,
        dc_name varchar(128) not null,
        petType integer not null,
        dc_price integer not null,
        dc_reserved boolean not null,
        dc_sex integer not null,
        dc_sold boolean not null,
        primary key (dc_id)
    );

    create table petshop_petorder (
       dc_id integer not null,
        dc_date timestamp,
        dc_name integer not null,
        primary key (dc_id)
    );
create unique index UK_PET_NAME on petshop_pet (dc_name);

    alter table petshop_petorder 
       add constraint FK_PET_ORDER 
       foreign key (dc_name) 
       references petshop_pet;
