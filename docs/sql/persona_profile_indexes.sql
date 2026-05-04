create unique index if not exists ux_persona_profile_source_id
    on persona_profile (source_id)
    where source_id is not null;

create index if not exists ix_persona_profile_source
    on persona_profile (source);

create index if not exists ix_persona_profile_active
    on persona_profile (active);

create index if not exists ix_persona_profile_age_group
    on persona_profile (age_group);

create index if not exists ix_persona_profile_province
    on persona_profile (province);

create index if not exists ix_persona_profile_district
    on persona_profile (district);

create index if not exists ix_persona_profile_occupation
    on persona_profile (occupation);

create index if not exists ix_persona_profile_source_active_province
    on persona_profile (source, active, province);

create index if not exists ix_persona_profile_source_active_age_group
    on persona_profile (source, active, age_group);
