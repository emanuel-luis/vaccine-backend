package io.github.emanuelluis.vaccine.api.fact;

import java.util.Date;

public interface IFactAplicacaoVacina {
    long getIdUnidadeBasica();

    Date getDataAplicacao();

    long getIdadeSemanas();

    String getGenero();

    long getCount();
}
