package io.github.emanuelluis.vaccine.api.dimension;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.*;

@Repository
public interface UnidadeBasicaRepository extends JpaRepository<UnidadeBasica, Long> {

    @Query(
            value = "\n" + "SELECT ub.id            AS \"id\",\n"
                    + "       ub.nome_fantasia AS \"nome\",\n"
                    + "       ub.logradouro    AS \"logradouro\",\n"
                    + "       ub.bairro        AS \"bairro\",\n"
                    + "       m.id             AS \"idMunicipio\",\n"
                    + "       m.nome           AS \"municipio\",\n"
                    + "       e.id             AS \"idEstado\",\n"
                    + "       e.nome           AS \"estado\",\n"
                    + "       e.sigla          AS \"siglaEstado\",\n"
                    + "       r.id             AS \"idRegiao\",\n"
                    + "       r.nome           AS \"regiao\"\n"
                    + "FROM unidade_basica ub\n"
                    + "   LEFT JOIN municipio m on ub.id_municipio = m.id\n"
                    + "   LEFT JOIN estado e on e.id = m.id_estado\n"
                    + "   LEFT JOIN regiao r on e.id_regiao = r.id",
            nativeQuery = true)
    public Page<IDimensionFilial> getDimensionFilial(Pageable pageable);

    public Page<UnidadeBasica> findByMunicipioNomeLike(String nomeMunicipio, Pageable pageable);

    public UnidadeBasica findByNomeFantasiaLike(String nomeFantasia);

    public Page<UnidadeBasica> findByMunicipioEstadoNomeLike(String nomeEstado, Pageable pageable);

    public Page<UnidadeBasica> findByMunicipioNomeLikeAndMunicipioEstadoNomeLike(
            String nomeMunicipio, String nomeEstado, Pageable pageable);
}
