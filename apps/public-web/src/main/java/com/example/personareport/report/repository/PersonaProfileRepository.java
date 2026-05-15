package com.example.personareport.report.repository;

import com.example.personareport.report.domain.PersonaProfile;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonaProfileRepository extends JpaRepository<PersonaProfile, Long> {

    long countBySource(String source);

    List<PersonaProfile> findBySourceAndActiveTrue(String source);

    List<PersonaProfile> findByActiveTrue();

    @Query("select coalesce(min(persona.id), 0) from PersonaProfile persona where persona.source = :source and persona.active = true")
    long findMinActiveIdBySource(@Param("source") String source);

    @Query("select coalesce(max(persona.id), 0) from PersonaProfile persona where persona.source = :source and persona.active = true")
    long findMaxActiveIdBySource(@Param("source") String source);

    @Query(value = """
            select *
            from persona_profile
            where source = :source
              and active = true
              and id >= :startId
              and (
                :keyword is null
                or lower(coalesce(occupation, '') || ' ' || coalesce(persona_summary, '') || ' ' || coalesce(interests, '') || ' ' || coalesce(pain_points, '')) like concat('%', :keyword, '%')
              )
            order by id
            limit :limit
            """, nativeQuery = true)
    List<PersonaProfile> findCandidateWindow(
            @Param("source") String source,
            @Param("startId") long startId,
            @Param("keyword") String keyword,
            @Param("limit") int limit
    );
}
