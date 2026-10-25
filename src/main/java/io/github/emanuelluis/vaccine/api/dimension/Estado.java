package io.github.emanuelluis.vaccine.api.dimension;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "estado")
public class Estado {
    @Id
    @Column(updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_regiao")
    private Regiao regiao;

    @Column
    private String nome;

    @Column
    private String sigla;
}
