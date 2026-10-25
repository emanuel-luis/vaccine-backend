package io.github.emanuelluis.vaccine.api.dimension;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "regiao")
public class Regiao {
    @Id
    @Column(updatable = false)
    private Long id;

    @Column
    private String nome;
}
