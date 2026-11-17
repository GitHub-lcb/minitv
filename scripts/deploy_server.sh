#!/usr/bin/env bash
# Idempotent MiniTV API deploy to the project server.
# Invoked only through: python3 scripts/release.py run --target server -- ./scripts/deploy_server.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${MINITV_DEPLOY_HOST:-byteplus-sg-1}"
REMOTE_DIR="${MINITV_REMOTE_DIR:-/opt/minitv}"
API_PORT="${MINITV_API_PORT:-18110}"

if [[ ! -f "$ROOT/.env" ]]; then
  echo "missing $ROOT/.env" >&2
  exit 1
fi

# Production secrets stay on the server. Never copy the local dev .env.
# Skip regeneration when the server already has an env file.
if ! ssh -o BatchMode=yes "$HOST" "test -f '$REMOTE_DIR/.env'"; then
python3 - "$ROOT/.env" << 'PY'
import secrets
import sys
from pathlib import Path

src = Path(sys.argv[1])
out = Path("/tmp/minitv-production.env")
keep_prefixes = ("BUNNY_",)
keep_exact = {"R2_ACCOUNT_ID", "R2_BUCKET", "R2_S3_ENDPOINT", "R2_PUBLIC_BASE_URL"}
lines = []
for raw in src.read_text().splitlines():
    line = raw.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    key, value = line.split("=", 1)
    if key.startswith(keep_prefixes) or key in keep_exact:
        lines.append(f"{key}={value}")

mysql_password = secrets.token_urlsafe(24)
redis_password = secrets.token_urlsafe(24)
lines.extend([
    "APP_ENV=production",
    "API_HOST=127.0.0.1",
    "API_PORT=18110",
    "MYSQL_USER=minitv",
    f"MYSQL_PASSWORD={mysql_password}",
    "MYSQL_DB=minitv",
    f"DATABASE_URL=mysql://minitv:{mysql_password}@127.0.0.1:3306/minitv",
    f"REDIS_PASSWORD={redis_password}",
    f"REDIS_URL=redis://:{redis_password}@127.0.0.1:6379/0",
    "CORS_ORIGINS=https://minitv.us,https://www.minitv.us,https://admin.minitv.us,https://h5.minitv.us",
    "SITE_DOMAIN=minitv.us",
    "SITE_URL=https://minitv.us",
    "WWW_URL=https://www.minitv.us",
    "API_BASE_URL=https://api.minitv.us",
    "ADMIN_URL=https://admin.minitv.us",
    "API_SERVER_IP=YOUR_SERVER_IP",
])
out.write_text("\n".join(lines) + "\n")
out.chmod(0o600)
print(f"wrote {out} ({len(lines)} keys, local .env not copied)")
PY
else
  echo "server env already exists; not regenerating secrets"
fi

echo "→ build Java service locally"
( cd "$ROOT/server" && mvn -B -q -DskipTests package )

echo "→ sync code to ${HOST}:${REMOTE_DIR}"
ssh -o BatchMode=yes "$HOST" "mkdir -p '$REMOTE_DIR'"
rsync -az "$ROOT/docker-compose.yml" "$HOST:$REMOTE_DIR/docker-compose.yml"
scp -q "$ROOT/server/target/minitv-server-2.0.0.jar" "$HOST:$REMOTE_DIR/minitv-server.jar"
if [[ -f /tmp/minitv-production.env ]]; then
  scp -q /tmp/minitv-production.env "$HOST:/tmp/minitv-production.env"
  rm -f /tmp/minitv-production.env
fi

echo "→ install runtime and start API on ${HOST}"
ssh -o BatchMode=yes "$HOST" "API_PORT='$API_PORT' REMOTE_DIR='$REMOTE_DIR' bash -s" << 'REMOTE'
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
if ! command -v nginx >/dev/null || ! command -v mysql >/dev/null || ! command -v redis-server >/dev/null || ! command -v java >/dev/null; then
  apt-get update -qq
  apt-get install -y -qq nginx mysql-server redis-server openjdk-21-jre-headless rsync
fi

install -d -m 755 "$REMOTE_DIR"
if [[ ! -f "$REMOTE_DIR/.env" ]]; then
  install -m 600 /tmp/minitv-production.env "$REMOTE_DIR/.env"
  echo "installed new production env"
else
  echo "keeping existing production env"
fi
rm -f /tmp/minitv-production.env
set -a
# shellcheck disable=SC1091
source "$REMOTE_DIR/.env"
set +a

# Status-machine triggers need this on every restart (binlog is on by default).
printf '[mysqld]\nlog_bin_trust_function_creators = 1\n' > /etc/mysql/mysql.conf.d/minitv.cnf
mysql -uroot << SQL
CREATE DATABASE IF NOT EXISTS minitv CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'minitv'@'127.0.0.1' IDENTIFIED BY '${MYSQL_PASSWORD}';
ALTER USER 'minitv'@'127.0.0.1' IDENTIFIED BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON minitv.* TO 'minitv'@'127.0.0.1';
SET GLOBAL log_bin_trust_function_creators = 1;
FLUSH PRIVILEGES;
SQL

if ! grep -q '^requirepass ' /etc/redis/redis.conf; then
  printf '\nrequirepass %s\n' "$REDIS_PASSWORD" >> /etc/redis/redis.conf
else
  sed -i "s/^requirepass .*/requirepass ${REDIS_PASSWORD}/" /etc/redis/redis.conf
fi
sed -i 's/^bind .*/bind 127.0.0.1 ::1/' /etc/redis/redis.conf
systemctl enable --now mysql redis-server
systemctl restart redis-server

cd "$REMOTE_DIR"
set -a
# shellcheck disable=SC1091
source "$REMOTE_DIR/.env"
set +a
# Flyway creates every table on boot; the seed runner creates the first admin
# account only when admin_users is empty (safe to re-run).
SEED_ADMIN_RUN=1 java -jar "$REMOTE_DIR/minitv-server.jar" --spring.main.web-application-type=none

cat > /etc/systemd/system/minitv-api.service << UNIT
[Unit]
Description=MiniTV API
After=network.target mysql.service redis-server.service

[Service]
Type=simple
WorkingDirectory=$REMOTE_DIR
EnvironmentFile=$REMOTE_DIR/.env
ExecStart=/usr/bin/java -jar $REMOTE_DIR/minitv-server.jar
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable minitv-api
systemctl restart minitv-api

# Cloudflare SSL mode is Full, so the origin must answer on 443.
# A self-signed cert is enough; visitors only see Cloudflare's certificate.
install -d -m 755 /etc/nginx/ssl
if [[ ! -f /etc/nginx/ssl/api.minitv.us.crt ]]; then
  openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
    -keyout /etc/nginx/ssl/api.minitv.us.key \
    -out /etc/nginx/ssl/api.minitv.us.crt \
    -subj "/CN=api.minitv.us" \
    -addext "subjectAltName=DNS:api.minitv.us"
  chmod 600 /etc/nginx/ssl/api.minitv.us.key
fi
cat > /etc/nginx/sites-available/minitv-api << NGINX
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name api.minitv.us _;
    client_max_body_size 32m;

    location / {
        proxy_pass http://127.0.0.1:${API_PORT};
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 60s;
    }
}

server {
    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;
    server_name api.minitv.us _;
    ssl_certificate /etc/nginx/ssl/api.minitv.us.crt;
    ssl_certificate_key /etc/nginx/ssl/api.minitv.us.key;
    client_max_body_size 32m;

    location / {
        proxy_pass http://127.0.0.1:${API_PORT};
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 60s;
    }
}
NGINX
rm -f /etc/nginx/sites-enabled/default
ln -sfn /etc/nginx/sites-available/minitv-api /etc/nginx/sites-enabled/minitv-api
nginx -t
systemctl enable nginx
systemctl reload nginx

for i in 1 2 3 4 5 6 7 8 9 10; do
  if curl -fsS "http://127.0.0.1:${API_PORT}/health" | grep -q '"status"'; then
    echo "health ok"
    curl -fsS "http://127.0.0.1:${API_PORT}/health"
    echo
    exit 0
  fi
  sleep 1
done
echo "health check failed" >&2
journalctl -u minitv-api -n 40 --no-pager >&2 || true
exit 1
REMOTE
