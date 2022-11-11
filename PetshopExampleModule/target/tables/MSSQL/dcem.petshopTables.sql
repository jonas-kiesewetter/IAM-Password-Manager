
    create table petshop_orderseq (
       next_val bigint
    );

    insert into petshop_orderseq values ( 1 );

    create table petshop_pet (
       dc_id int not null,
        dc_age int not null,
        dc_name varchar(128) not null,
        petType int not null,
        dc_price int not null,
        dc_reserved bit not null,
        dc_sex int not null,
        dc_sold bit not null,
        primary key (dc_id)
    );

    create table petshop_petorder (
       dc_id int not null,
        dc_date datetime2,
        dc_name int not null,
        primary key (dc_id)
    );

    create table petshop_petseq (
       next_val bigint
    );

    insert into petshop_petseq values ( 1 );

    alter table petshop_pet 
       add constraint UK_PET_NAME unique (dc_name);

    alter table petshop_petorder 
       add constraint FK_PET_ORDER 
       foreign key (dc_name) 
       references petshop_pet;
