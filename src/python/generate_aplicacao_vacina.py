from datetime import timedelta, datetime
from faker import Faker
from itertools import count
import random
import pandas as pd
from sqlalchemy import create_engine
from sqlalchemy.sql import insert, table, column
import os
import uuid
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parent / "data"

# Connection from the libpq variables (PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD) or DATABASE_URL.
engine = create_engine(os.environ.get("DATABASE_URL", "postgresql+psycopg2://"), isolation_level="AUTOCOMMIT")


df0 = pd.read_csv(DATA_DIR / "unidade_basica.csv")
df1 = pd.read_csv(DATA_DIR / "municipio.csv")
df2 = pd.read_csv(DATA_DIR / "estado.csv")

df = df0.merge(
    df1, how="inner", left_on="id_municipio", right_on="id", suffixes=("", "_m0")
).merge(df2, how="inner", left_on="id_estado", right_on="id", suffixes=("", "_e0"))

df = pd.concat(
    [
        df,
        *[df.query("sigla == 'SP'")] * 8,
        *[df.query("sigla == 'MG'")] * 3,
        *[df.query("sigla == 'RJ'")] * 7,
        *[df.query("sigla == 'MA'")] * 1,
        *[df.query("sigla == 'PE'")] * 4,
        *[df.query("id_regiao == 4")] * 6,
        *[df.query("id_regiao == 5")] * 4,
        *[df.query("id_regiao == 2")] * 2,
        *[df.query("id_regiao == 1")] * 1,
    ]
)

fake = Faker("pt-br")
with engine.connect() as connection:
    dt_ini = datetime.now()
    for _ in count():
        data_nascimento = fake.date_of_birth()
        sexo = random.choice("F" * 8 + "M" * 2)
        id_unidade_basica = random.choice(df.id.values).item()
        datahora_aplicacao = fake.date_time_between(
            data_nascimento + timedelta(weeks=8), 
            data_nascimento + timedelta(weeks=28)
        )

        query = insert(
            table(
                "aplicacao_vacina",
                column("id"),
                column("data_nascimento"),
                column("sexo"),
                column("id_unidade_basica"),
                column("datahora_aplicacao"),
            )
        ).values(
            [uuid.uuid4(), data_nascimento, sexo, id_unidade_basica, datahora_aplicacao]
        )

        connection.execute(query)

        if (datetime.now() - dt_ini).total_seconds() > timedelta(hours=1).total_seconds():
            break