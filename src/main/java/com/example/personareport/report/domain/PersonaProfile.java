package com.example.personareport.report.domain;

import com.example.personareport.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PersonaProfile extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = true)
    private String source;

    private String sourceId;

    private Integer age;

    private String ageGroup;

    private String gender;

    private String region;

    private String province;

    private String district;

    private String occupation;

    @Column(columnDefinition = "TEXT")
    private String personaSummary;

    @Column(columnDefinition = "TEXT")
    private String interests;

    @Column(columnDefinition = "TEXT")
    private String painPoints;

    private String digitalFamiliarity;

    private String buyingSensitivity;

    @Column(columnDefinition = "TEXT")
    private String rawData;

    @Column(nullable = false)
    private boolean active;

    private PersonaProfile(
            String source,
            String sourceId,
            Integer age,
            String ageGroup,
            String gender,
            String region,
            String province,
            String district,
            String occupation,
            String personaSummary,
            String interests,
            String painPoints,
            String digitalFamiliarity,
            String buyingSensitivity,
            String rawData,
            boolean active
    ) {
        this.source = source;
        this.sourceId = sourceId;
        this.age = age;
        this.ageGroup = ageGroup;
        this.gender = gender;
        this.region = region;
        this.province = province;
        this.district = district;
        this.occupation = occupation;
        this.personaSummary = personaSummary;
        this.interests = interests;
        this.painPoints = painPoints;
        this.digitalFamiliarity = digitalFamiliarity;
        this.buyingSensitivity = buyingSensitivity;
        this.rawData = rawData;
        this.active = active;
    }

    public static PersonaProfile create(
            String source,
            String sourceId,
            Integer age,
            String ageGroup,
            String gender,
            String region,
            String province,
            String district,
            String occupation,
            String personaSummary,
            String interests,
            String painPoints,
            String digitalFamiliarity,
            String buyingSensitivity,
            String rawData,
            boolean active
    ) {
        return new PersonaProfile(
                source,
                sourceId,
                age,
                ageGroup,
                gender,
                region,
                province,
                district,
                occupation,
                personaSummary,
                interests,
                painPoints,
                digitalFamiliarity,
                buyingSensitivity,
                rawData,
                active
        );
    }
}
