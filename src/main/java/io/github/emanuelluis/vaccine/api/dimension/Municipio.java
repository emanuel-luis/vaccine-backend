package io.github.emanuelluis.vaccine.api.dimension;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "municipio")
public class Municipio {
    @Id
    @Column(updatable = false)
    private Long id;

    @Column
    private String nome;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_estado")
    private Estado estado;
}
