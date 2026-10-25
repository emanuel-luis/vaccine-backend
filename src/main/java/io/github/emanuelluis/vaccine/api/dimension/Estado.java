package io.github.emanuelluis.vaccine.api.dimension;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @Column(length = 2)
    @JdbcTypeCode(SqlTypes.CHAR) // CHAR(2) in V1
    private String sigla;
}
