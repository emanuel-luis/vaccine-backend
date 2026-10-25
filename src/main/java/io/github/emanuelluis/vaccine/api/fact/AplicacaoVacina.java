package io.github.emanuelluis.vaccine.api.fact;

import io.github.emanuelluis.vaccine.api.dimension.UnidadeBasica;
import jakarta.persistence.*;
import java.util.Date;
import java.util.UUID;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "aplicacao_vacina")
public class AplicacaoVacina {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false)
    private UUID id;

    @Column(name = "data_nascimento", nullable = false)
    private Date dataNascimento;

    @Column(name = "sexo", nullable = false, length = 1)
    private String sexo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_unidade_basica")
    private UnidadeBasica unidadeBasica;

    @Column(name = "datahora_aplicacao", nullable = false, updatable = false)
    private Date dataHoraAplicacao;
}
