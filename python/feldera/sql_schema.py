from typing import Mapping


class SQLSchema:
    # TODO: should capture nullability of columns, model after the rust `Relation` type
    schema: Mapping[str, str]

    def __init__(self, schema: Mapping[str, str]):
        self.schema = schema

    def build_ddl(self, table_name: str) -> str:
        ddl = f"CREATE TABLE {table_name} (\n"
        for column_name, column_type in self.schema.items():
            ddl += f"  {column_name} {column_type},\n"
        ddl = ddl[:-2] + "\n);"
        return ddl
