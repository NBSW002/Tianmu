"""Provision the site's SELECT-only Trace reader. Never print credentials or database errors."""
import argparse
import hashlib
import os
from pathlib import Path
import secrets
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--config', type=Path, required=True)
parser.add_argument('--clickhouse-container', default='tianmu-staging-clickhouse-1')
args = parser.parse_args()
directory = args.config.resolve() / 'secrets'
directory.mkdir(parents=True, mode=0o700, exist_ok=True)
password_file = directory / 'trace-query-password'
if not password_file.exists():
    descriptor = os.open(str(password_file), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
    with os.fdopen(descriptor, 'w') as output:
        output.write(secrets.token_hex(32))
    os.chown(str(password_file), 10001, 10001)
password = password_file.read_text().strip()
if len(password) < 32:
    raise SystemExit('Trace reader secret does not meet length requirements')
digest = hashlib.sha256(password.encode()).hexdigest()
sql = ("CREATE USER IF NOT EXISTS trace_reader IDENTIFIED WITH sha256_hash BY '" + digest + "';\n"
       "GRANT SELECT ON tianmu_trace.trace_spans TO trace_reader;\n"
       "GRANT SELECT ON tianmu_trace.trace_spans_current TO trace_reader;\n"
       "ALTER USER trace_reader SETTINGS readonly=2;\n")
result = subprocess.run(['docker', 'exec', '-i', args.clickhouse_container, 'sh', '-c',
    'exec clickhouse-client --user trace_admin --password "$(cat /run/secrets/clickhouse-password)" --multiquery'],
    input=sql.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
if result.returncode:
    raise SystemExit('Trace reader provisioning failed; inspect private server diagnostics')
print('Trace reader SELECT grants prepared; verify authentication through the application before activation.')
