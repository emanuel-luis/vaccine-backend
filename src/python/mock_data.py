"""Load the legacy dimension CSVs into the Flyway-managed schema (idempotent: truncate, then load).

Usage: `make seed`. Connection comes from the libpq variables (PGHOST, PGPORT, PGDATABASE, PGUSER,
PGPASSWORD) or from DATABASE_URL when set.
The schema is created by the API's Flyway migrations (start the API or `make up` first).
"""

import os
from pathlib import Path

import pandas as pd
from sqlalchemy import create_engine
from sqlalchemy.sql import text

DATA_DIR = Path(__file__).resolve().parent / "data"
TABLES = ["regiao", "estado", "municipio", "unidade_basica"]

engine = create_engine(os.environ.get("DATABASE_URL", "postgresql+psycopg2://"))
with engine.begin() as connection:
    connection.execute(text(f"TRUNCATE {', '.join(TABLES)} CASCADE"))
    for table in TABLES:
        frame = pd.read_csv(DATA_DIR / f"{table}.csv")
        frame.to_sql(table, con=connection, if_exists="append", index=False)
        print(f"{table}: {len(frame)} rows")
