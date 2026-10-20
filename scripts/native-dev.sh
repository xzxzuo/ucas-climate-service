#!/usr/bin/env bash
# Dedicated local development databases. All state remains inside this project.
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
runtime="$project_dir/.runtime"
mysql_base="${CLIMATE_MYSQL_BASE:-/usr/local/mysql}"
redis_base="$runtime/redis-7.4.11/src"
jar="$project_dir/target/ucas-climate-service-1.0.0.jar"
mkdir -p "$runtime/logs"

auth() {
  python3 scripts/local_auth.py
  while IFS='=' read -r key value; do
    case "$key" in
      CLIMATE_READ_KEY) export CLIMATE_READ_KEY="${CLIMATE_READ_KEY:-$value}";;
      CLIMATE_ADMIN_KEY) export CLIMATE_ADMIN_KEY="${CLIMATE_ADMIN_KEY:-$value}";;
      CLIMATE_PLATFORM_ADMIN_PASSWORD) export CLIMATE_PLATFORM_ADMIN_PASSWORD="${CLIMATE_PLATFORM_ADMIN_PASSWORD:-$value}";;
    esac
  done < "$runtime/auth.env"
}

services() {
  if ! test -x "$mysql_base/bin/mysqld"; then echo 'MySQL not found; use Docker Compose or set CLIMATE_MYSQL_BASE.';exit 1;fi
  if ! test -x "$redis_base/redis-server"; then echo 'Run: bash scripts/bootstrap-redis.sh';exit 1;fi
  if ! test -d "$runtime/mysql-data/mysql"; then
    "$mysql_base/bin/mysqld" --no-defaults --initialize-insecure --basedir="$mysql_base" --datadir="$runtime/mysql-data" >"$runtime/logs/mysql-init.log" 2>&1
  fi
  if ! "$mysql_base/bin/mysqladmin" --no-defaults --socket="$runtime/mysql.sock" -uroot ping >/dev/null 2>&1; then
    nohup "$mysql_base/bin/mysqld" --no-defaults --basedir="$mysql_base" --datadir="$runtime/mysql-data" --socket="$runtime/mysql.sock" --pid-file="$runtime/mysql.pid" --port=13306 --bind-address=127.0.0.1 --mysqlx=0 >"$runtime/logs/mysql.log" 2>&1 &
    for i in {1..50}; do if "$mysql_base/bin/mysqladmin" --no-defaults --socket="$runtime/mysql.sock" -uroot ping >/dev/null 2>&1; then break;fi;sleep .2;done
  fi
  "$mysql_base/bin/mysql" --no-defaults --socket="$runtime/mysql.sock" -uroot -e "CREATE DATABASE IF NOT EXISTS climate_rebuild CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE DATABASE IF NOT EXISTS climate_rebuild_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER IF NOT EXISTS 'climate'@'localhost' IDENTIFIED BY 'climate-local'; GRANT ALL ON climate_rebuild.* TO 'climate'@'localhost'; GRANT ALL ON climate_rebuild_test.* TO 'climate'@'localhost';"
  if ! "$redis_base/redis-cli" -h 127.0.0.1 -p 16379 ping >/dev/null 2>&1; then
    "$redis_base/redis-server" --port 16379 --bind 127.0.0.1 --save '' --appendonly no --daemonize yes --pidfile "$runtime/redis.pid" --logfile "$runtime/logs/redis.log"
  fi
}
stop_app() {
  if test -f "$runtime/app.pid"; then
    app_pid="$(< "$runtime/app.pid")"
    running_command="$(ps -p "$app_pid" -o command= || true)"
    case "$running_command" in *"$jar"*) kill "$app_pid";for i in {1..50}; do if ! kill -0 "$app_pid" 2>/dev/null;then break;fi;sleep .2;done;; esac
  fi
}
case "${1:-start}" in
  services) services;;
  start|run)
    auth
    services
    if test -f "$runtime/app.pid" && kill -0 "$(< "$runtime/app.pid")" 2>/dev/null; then echo 'Application already running; use restart.';exit 0;fi
    test -f data/sample.csv || { echo 'Run python3 scripts/sample_source.py first.';exit 1; }
    mvn -q -DskipTests package
    if test "${1:-start}" = run; then
      printf '%s\n' "$$" >"$runtime/app.pid"
      exec java -jar "$jar" --app.import-file="$project_dir/data/sample.csv" --app.import-if-empty
    fi
    nohup java -jar "$jar" --app.import-file="$project_dir/data/sample.csv" --app.import-if-empty >"$runtime/logs/startup.log" 2>&1 &
    printf '%s\n' "$!" >"$runtime/app.pid"
    echo 'Starting: http://127.0.0.1:8087 ; import and analysis progress: .runtime/logs/startup.log'
    ;;
  restart) stop_app;exec bash "$0" start;;
  stop)
    stop_app
    "$mysql_base/bin/mysqladmin" --no-defaults --socket="$runtime/mysql.sock" -uroot shutdown 2>/dev/null || true
    if test -f "$runtime/redis.pid";then "$redis_base/redis-cli" -h 127.0.0.1 -p 16379 shutdown nosave 2>/dev/null || true;fi
    echo 'Project services stopped. Database files retained.'
    ;;
  auth) auth;;
  test) services;CLIMATE_IT=true mvn -B -ntp test;python3 scripts/test_sampler.py;;
  *) echo 'Usage: bash scripts/native-dev.sh {start|run|restart|services|stop|test|auth}';exit 2;;
esac
