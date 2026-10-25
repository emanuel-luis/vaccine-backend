package io.github.emanuelluis.vaccine.api.fact;

import java.util.Date;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.*;

@Repository
public interface AplicacaoVacinaRepository extends JpaRepository<AplicacaoVacina, UUID> {

    @Query(
            value = "\n" + "SELECT id_unidade_basica          AS idUnidadeBasica,\n"
                    + "       data_aplicacao             AS dataAplicacao,\n"
                    + "       cast(idade_semanas as int) AS idadeSemanas,\n"
                    + "       sexo                       AS genero,\n"
                    + "       count(*)                   AS count\n"
                    + "FROM (\n"
                    + "       SELECT id,\n"
                    + "             data_nascimento,\n"
                    + "             sexo,\n"
                    + "             id_unidade_basica,\n"
                    + "             datahora_aplicacao,\n"
                    + "             floor(EXTRACT(days FROM datahora_aplicacao - data_nascimento) / 7) AS idade_semanas,\n"
                    + "             date(datahora_aplicacao)                                           AS data_aplicacao\n"
                    + "      FROM aplicacao_vacina) prepared\n"
                    + "WHERE data_aplicacao >= :start\n"
                    + "      AND data_aplicacao < :end\n"
                    + "GROUP BY id_unidade_basica, data_aplicacao, idade_semanas, sexo\n",
            nativeQuery = true)
    public Page<IFactAplicacaoVacina> getFactAplicacaoVacina(
            @Param("start") Date start, @Param("end") Date end, Pageable pageable);
}
