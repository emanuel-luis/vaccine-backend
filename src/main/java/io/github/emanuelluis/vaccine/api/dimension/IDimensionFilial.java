package io.github.emanuelluis.vaccine.api.dimension;

public interface IDimensionFilial {
    public long getId();

    public String getNome();

    public String getLogradouro();

    public String getBairro();

    public long getIdMunicipio();

    public String getMunicipio();

    public long getIdEstado();

    public String getEstado();

    public String getSiglaEstado();

    public long getIdRegiao();

    public String getRegiao();
}
