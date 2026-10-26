package io.github.emanuelluis.vaccine.campaigns;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code app.campaign}; every write is one statement, the version check is part of its WHERE. */
@Repository
@Transactional
public class CampaignRepository {

    static final String SOURCE = "app.campaign";

    private static final String COLUMNS = """
            campaign_id, name, vaccine_code, dose_label, region_scope, starts_on, ends_on, target_doses,
            created_by, created_at, updated_at, version""";

    private final JdbcClient jdbc;

    public CampaignRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Campaign> list() {
        return jdbc.sql("select " + COLUMNS + " from app.campaign order by starts_on desc, name, campaign_id")
                .query(Campaign.class)
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<Campaign> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from app.campaign where campaign_id = :id")
                .param("id", id)
                .query(Campaign.class)
                .optional();
    }

    /** True when {@code marts.dim_vaccine} knows the vaccine (and the dose, when given). */
    @Transactional(readOnly = true)
    public boolean vaccineExists(String vaccineCode, String doseLabel) {
        return jdbc.sql("""
                        select exists (
                            select 1 from marts.dim_vaccine
                            where vaccine_code = :vaccine and vaccine_code <> 'UNKNOWN'
                              and (cast(:dose as text) is null or dose_label = cast(:dose as text)))
                        """)
                .param("vaccine", vaccineCode)
                .param("dose", doseLabel)
                .query(Boolean.class)
                .single();
    }

    public Campaign insert(UUID id, CampaignRequest r, String createdBy) {
        return jdbc.sql("""
                        insert into app.campaign (campaign_id, name, vaccine_code, dose_label, region_scope,
                                                  starts_on, ends_on, target_doses, created_by)
                        values (:id, :name, :vaccine, :dose, :region, :starts, :ends, :target, :by)
                        returning """ + " " + COLUMNS)
                .param("id", id)
                .param("name", r.name().strip())
                .param("vaccine", r.vaccineCode())
                .param("dose", r.doseLabel())
                .param("region", r.regionScope())
                .param("starts", r.startsOn())
                .param("ends", r.endsOn())
                .param("target", r.targetDoses())
                .param("by", createdBy)
                .query(Campaign.class)
                .single();
    }

    /** The updated row, or empty when the row is gone or its version is not {@code version}. */
    public Optional<Campaign> update(UUID id, int version, CampaignRequest r) {
        return jdbc.sql("""
                        update app.campaign
                        set name = :name, vaccine_code = :vaccine, dose_label = :dose, region_scope = :region,
                            starts_on = :starts, ends_on = :ends, target_doses = :target,
                            version = version + 1, updated_at = now()
                        where campaign_id = :id and version = :version
                        returning """ + " " + COLUMNS)
                .param("id", id)
                .param("version", version)
                .param("name", r.name().strip())
                .param("vaccine", r.vaccineCode())
                .param("dose", r.doseLabel())
                .param("region", r.regionScope())
                .param("starts", r.startsOn())
                .param("ends", r.endsOn())
                .param("target", r.targetDoses())
                .query(Campaign.class)
                .optional();
    }

    /** True when a row with this version was deleted. */
    public boolean delete(UUID id, int version) {
        return jdbc.sql("delete from app.campaign where campaign_id = :id and version = :version")
                        .param("id", id)
                        .param("version", version)
                        .update()
                > 0;
    }
}
