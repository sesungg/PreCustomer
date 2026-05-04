import sys
import pandas as pd

if len(sys.argv) < 2:
    print("Usage: python scripts/inspect_parquet.py nemotron_personas_korea.parquet")
    sys.exit(1)

path = sys.argv[1]

df = pd.read_parquet(path)

print("Rows:", len(df))
print("Columns:")
for col in df.columns:
    print("-", col)

print("\nSample:")
print(df.head(3).to_string())