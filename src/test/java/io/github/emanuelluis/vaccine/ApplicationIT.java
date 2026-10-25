package io.github.emanuelluis.vaccine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Boots the whole app against a real Postgres: Flyway migrates, Hibernate validates the schema. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApplicationIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE regiao, estado, municipio, unidade_basica, aplicacao_vacina, recebimento_vacina");
        jdbc.update("INSERT INTO regiao (id, nome) VALUES (2, 'Nordeste')");
        jdbc.update("INSERT INTO estado (id, id_regiao, nome, sigla) VALUES (21, 2, 'Maranhão', 'MA')");
        jdbc.update("INSERT INTO municipio (id, id_estado, nome) VALUES (211130, 21, 'São Luís')");
        jdbc.update("INSERT INTO unidade_basica (id, id_municipio, nome_fantasia, bairro, logradouro)"
                + " VALUES (2453045, 211130, 'UBS CENTRO', 'CENTRO', 'RUA A - S/N')");
        jdbc.update("INSERT INTO aplicacao_vacina (id, data_nascimento, sexo, id_unidade_basica, datahora_aplicacao)"
                + " VALUES (gen_random_uuid(), '2023-01-10', 'F', 2453045, '2023-04-01 10:00')");
    }

    @Test
    void flywayAppliedTheBaseline() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM ops.flyway_schema_history WHERE success AND version = '1'", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void dimensionEndpointJoinsTheGeographyHierarchy() throws Exception {
        mvc.perform(get("/api/dimension"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(2453045))
                .andExpect(jsonPath("$[0].siglaEstado").value("MA"))
                .andExpect(jsonPath("$[0].regiao").value("Nordeste"));
    }

    @Test
    void factEndpointAggregatesApplicationsInTheWindow() throws Exception {
        mvc.perform(get("/api/fact").param("start", "2023-01-01").param("end", "2023-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].idUnidadeBasica").value(2453045))
                .andExpect(jsonPath("$[0].count").value(1));
    }
}
