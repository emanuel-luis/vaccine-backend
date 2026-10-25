package io.github.emanuelluis.vaccine.api.dimension;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "unidade_basica")
public class UnidadeBasica {
    @Id
    @Column(updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_municipio")
    private Municipio municipio;

    @Column(name = "nome_fantasia")
    private String nomeFantasia;

    @Column
    private String bairro;

    @Column
    private String logradouro;
}
